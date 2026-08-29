package cr.ac.fractall.reportes.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.calculo.CalculadoraImpuestoLinea;
import cr.ac.fractall.facturacion.calculo.CalculadoraImpuestoLinea.ResultadoImpuestoLinea;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.reportes.dto.FilaDetalleIva;
import cr.ac.fractall.reportes.dto.FilaResumenIva;
import cr.ac.fractall.reportes.dto.ReporteIvaResponse;
import cr.ac.fractall.reportes.repositorio.FilaLineaComprobante;
import cr.ac.fractall.reportes.repositorio.ReporteIvaRepository;

/**
 * Agrega el débito fiscal de IVA de un período (Release 3 / Fase D, ver el diseño). Ejecuta
 * exactamente 2 consultas -- Q1 el theta-join de {@link ReporteIvaRepository#buscarLineasEnPeriodo}
 * y Q2 el lookup batcheado de {@link ImpuestoLineaExoneracionRepository#findByLineaIdIn} -- y hace
 * el traversal con signo y la agrupación por tarifa en memoria.
 *
 * <p>Traversal por período de emisión PROPIO del comprobante (no el de la factura referenciada):
 * signo {@code +1} para factura/ND/tiquete ({@code 01}/{@code 02}/{@code 04}), {@code -1} para NC
 * ({@code 03}), y falla cerrado ({@link IllegalStateException}) ante cualquier otro código -- un
 * código desconocido NUNCA debe asumirse {@code +1} en un reporte fiscal.
 *
 * <p>La clave de agrupación por tarifa normaliza el porcentaje con
 * {@code .setScale(2, HALF_UP)} ANTES de usarlo como llave de {@code Map}: {@code BigDecimal.equals}
 * es sensible a escala ({@code 13.0} != {@code 13.00}), así que sin la normalización dos lecturas
 * JDBC con distinta escala partirían silenciosamente una misma tarifa en dos filas.
 */
@Service
public class ReporteIvaService {

    private static final String ESTADO_ACEPTADO = "ACEPTADO";
    private static final long DIAS_MAXIMOS_RANGO = 366;
    private static final int CHUNK_MAXIMO = 1000;

    private final ReporteIvaRepository reporteIvaRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;

    public ReporteIvaService(
            ReporteIvaRepository reporteIvaRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository) {
        this.reporteIvaRepository = reporteIvaRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
    }

    @Transactional(readOnly = true)
    public ReporteIvaResponse generar(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);

        LocalDateTime desdeInclusive = desde.atStartOfDay();
        LocalDateTime hastaExclusiva = hasta.plusDays(1).atStartOfDay();

        List<FilaLineaComprobante> filas =
                reporteIvaRepository.buscarLineasEnPeriodo(ESTADO_ACEPTADO, desdeInclusive, hastaExclusiva);

        Map<UUID, ImpuestoLineaExoneracion> inlinePorLineaId = buscarInlinePorLineaId(filas);

        Map<ClaveTarifa, Acumulador> acumuladores = new LinkedHashMap<>();
        List<FilaDetalleIva> detalle = new ArrayList<>(filas.size());

        for (FilaLineaComprobante fila : filas) {
            int signo = signo(fila.tipoComprobante());

            LineaFactura lineaTransitoria = new LineaFactura();
            lineaTransitoria.setSubtotal(fila.subtotal());
            lineaTransitoria.setPorcentajeImpuestoAplicado(fila.porcentajeImpuestoAplicado());
            lineaTransitoria.setExoneracionId(fila.exoneracionId());
            lineaTransitoria.setMontoExoneracionAplicado(fila.montoExoneracionAplicado());

            ResultadoImpuestoLinea resultado =
                    CalculadoraImpuestoLinea.calcular(lineaTransitoria, inlinePorLineaId.get(fila.lineaId()));

            ClaveTarifa clave = new ClaveTarifa(fila.gravadoAplicado(), normalizarPorcentaje(fila.porcentajeImpuestoAplicado()));
            Acumulador acumulador = acumuladores.computeIfAbsent(clave, k -> new Acumulador());
            BigDecimal signoDecimal = BigDecimal.valueOf(signo);
            acumulador.baseImponible = acumulador.baseImponible.add(signoDecimal.multiply(fila.subtotal()));
            acumulador.impuestoBruto = acumulador.impuestoBruto.add(signoDecimal.multiply(resultado.impuestoBruto()));
            acumulador.exoneraciones = acumulador.exoneraciones.add(signoDecimal.multiply(resultado.montoExoneracion()));
            acumulador.impuestoNeto = acumulador.impuestoNeto.add(signoDecimal.multiply(resultado.impuestoNeto()));

            detalle.add(new FilaDetalleIva(
                    fila.fechaEmision().toLocalDate(),
                    fila.tipoComprobante(),
                    fila.consecutivo(),
                    fila.claveNumerica(),
                    fila.facturaId(),
                    fila.clienteId(),
                    fila.facturaReferenciaId(),
                    fila.numeroLinea(),
                    fila.gravadoAplicado(),
                    fila.porcentajeImpuestoAplicado(),
                    fila.subtotal(),
                    resultado.impuestoBruto(),
                    resultado.montoExoneracion(),
                    resultado.impuestoNeto(),
                    signo));
        }

        List<FilaResumenIva> resumen = acumuladores.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<ClaveTarifa, Acumulador> e) -> e.getKey().gravado())
                        .thenComparing(e -> e.getKey().porcentaje()))
                .map(e -> new FilaResumenIva(
                        e.getKey().gravado(),
                        e.getKey().porcentaje(),
                        e.getValue().baseImponible,
                        e.getValue().impuestoBruto,
                        e.getValue().exoneraciones,
                        e.getValue().impuestoNeto))
                .toList();

        BigDecimal totalDebitoFiscal = resumen.stream()
                .map(FilaResumenIva::impuestoNeto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReporteIvaResponse(desde, hasta, resumen, detalle, totalDebitoFiscal);
    }

    private void validarRango(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) {
            throw new RangoFechasInvalidaException(
                    "El rango de fechas es inválido: hasta (" + hasta + ") es anterior a desde (" + desde + ")");
        }
        long dias = ChronoUnit.DAYS.between(desde, hasta);
        if (dias > DIAS_MAXIMOS_RANGO) {
            throw new RangoFechasInvalidaException(
                    "El rango de fechas (" + dias + " días) excede el máximo permitido de "
                            + DIAS_MAXIMOS_RANGO + " días");
        }
    }

    /**
     * Q2: lookup batcheado de exoneraciones inline, troceado en bloques de {@link #CHUNK_MAXIMO}
     * para no exceder el techo de parámetros bind de PostgreSQL -- ver el diseño, sección "Fetch
     * strategy".
     */
    private Map<UUID, ImpuestoLineaExoneracion> buscarInlinePorLineaId(List<FilaLineaComprobante> filas) {
        List<UUID> lineaIds = filas.stream().map(FilaLineaComprobante::lineaId).toList();
        Map<UUID, ImpuestoLineaExoneracion> resultado = new LinkedHashMap<>();
        for (int inicio = 0; inicio < lineaIds.size(); inicio += CHUNK_MAXIMO) {
            List<UUID> bloque = lineaIds.subList(inicio, Math.min(inicio + CHUNK_MAXIMO, lineaIds.size()));
            for (ImpuestoLineaExoneracion inline : impuestoLineaExoneracionRepository.findByLineaIdIn(bloque)) {
                resultado.put(inline.getLineaId(), inline);
            }
        }
        return resultado;
    }

    private static BigDecimal normalizarPorcentaje(BigDecimal porcentaje) {
        return porcentaje.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Codigos verificados contra {@code TipoComprobantePerfil}: {@code 01} Factura, {@code 02}
     * Nota de Débito, {@code 03} Nota de Crédito, {@code 04} Tiquete. Un código desconocido falla
     * cerrado -- nunca se asume {@code +1}.
     */
    private static int signo(String tipoComprobante) {
        return switch (tipoComprobante) {
            case "01", "02", "04" -> 1;
            case "03" -> -1;
            default -> throw new IllegalStateException("tipo_comprobante desconocido: " + tipoComprobante);
        };
    }

    private record ClaveTarifa(boolean gravado, BigDecimal porcentaje) {
    }

    private static final class Acumulador {
        private BigDecimal baseImponible = BigDecimal.ZERO;
        private BigDecimal impuestoBruto = BigDecimal.ZERO;
        private BigDecimal exoneraciones = BigDecimal.ZERO;
        private BigDecimal impuestoNeto = BigDecimal.ZERO;
    }
}
