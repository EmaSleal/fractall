package cr.ac.fractall.reportes.servicio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.fe.TipoMedioPago;
import cr.ac.fractall.reportes.dto.CarteraPendiente;
import cr.ac.fractall.reportes.dto.ComparativoPeriodoAnterior;
import cr.ac.fractall.reportes.dto.FilaCobrosPorMedioPago;
import cr.ac.fractall.reportes.dto.FilaDetalleCobro;
import cr.ac.fractall.reportes.dto.FilaDetalleVenta;
import cr.ac.fractall.reportes.dto.FilaVentasPorCondicion;
import cr.ac.fractall.reportes.dto.ReporteFlujoCajaResponse;
import cr.ac.fractall.reportes.dto.SerieCobros;
import cr.ac.fractall.reportes.dto.SerieVentas;
import cr.ac.fractall.reportes.repositorio.ReporteFlujoCajaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Compone el reporte de flujo de caja (Release 3 / Fase D, Change 2 de 2, ver el diseño obs #918):
 * ventas y cobros como series independientes (nunca sumadas), cartera pendiente punto-en-el-tiempo,
 * y un comparativo del período inmediatamente anterior. Ejecuta las 5 consultas nativas de
 * {@link ReporteFlujoCajaRepository} (Q1-Q5, PR2/PR3 de este cambio) y hace TODO el traversal
 * (signo, agrupación, cartera, comparativo) en memoria, en una sola pasada por serie (Decisión B3).
 *
 * <p>{@code empresaId} se resuelve de {@link TenantContext} (no vía {@code @TenantId} de
 * Hibernate: las 5 consultas son nativas, ver el javadoc de {@link ReporteFlujoCajaRepository}) y
 * se pasa explícitamente a cada llamada.
 */
@Service
public class ReporteFlujoCajaService {

    private static final long DIAS_MAXIMOS_RANGO = 366;

    private final ReporteFlujoCajaRepository reporteFlujoCajaRepository;

    public ReporteFlujoCajaService(ReporteFlujoCajaRepository reporteFlujoCajaRepository) {
        this.reporteFlujoCajaRepository = reporteFlujoCajaRepository;
    }

    @Transactional(readOnly = true)
    public ReporteFlujoCajaResponse generar(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        UUID empresaId = TenantContext.get();

        LocalDateTime desdeInclusivo = desde.atStartOfDay();
        LocalDateTime hastaExclusivo = hasta.plusDays(1).atStartOfDay();
        LocalDate fechaCorte = hasta;
        LocalDateTime corteExclusivo = fechaCorte.plusDays(1).atStartOfDay();

        ResultadoVentas resultadoVentas = plegarVentas(
                reporteFlujoCajaRepository.buscarVentasEnPeriodo(empresaId, desdeInclusivo, hastaExclusivo));
        ResultadoCobros resultadoCobros = plegarCobros(
                reporteFlujoCajaRepository.buscarCobrosEnPeriodo(empresaId, desdeInclusivo, hastaExclusivo));
        CarteraPendiente cartera = calcularCartera(
                fechaCorte, reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(empresaId, corteExclusivo));
        ComparativoPeriodoAnterior comparativo = calcularComparativo(
                empresaId, desde, hasta, resultadoVentas.serie.total(), resultadoCobros.serie.total());

        return new ReporteFlujoCajaResponse(
                desde, hasta,
                resultadoVentas.serie, resultadoCobros.serie, cartera, comparativo,
                resultadoVentas.detalle, resultadoCobros.detalle);
    }

    /**
     * Q1 -- una sola pasada (Decisión B3): construye {@link SerieVentas}/
     * {@link FilaVentasPorCondicion} agregados por {@code condicion_venta} Y
     * {@code List<FilaDetalleVenta>} de la MISMA iteración, para que Resumen == suma(Detalle) por
     * construcción, nunca dos fuentes que puedan desincronizarse.
     */
    private ResultadoVentas plegarVentas(List<Object[]> filas) {
        Map<String, AcumuladorVenta> porCondicion = new LinkedHashMap<>();
        List<FilaDetalleVenta> detalle = new ArrayList<>(filas.size());
        BigDecimal total = BigDecimal.ZERO;

        for (Object[] fila : filas) {
            UUID facturaId = aUuid(fila[0]);
            String tipoComprobante = (String) fila[1];
            String consecutivo = (String) fila[2];
            LocalDate fechaEmision = aFecha(fila[3]);
            String condicionVenta = (String) fila[4];
            UUID clienteId = aUuid(fila[5]);
            String moneda = (String) fila[6];
            UUID facturaReferenciaId = aUuid(fila[7]);
            BigDecimal montoCrudo = (BigDecimal) fila[8];

            int signo = signo(tipoComprobante);
            BigDecimal montoSignado = montoCrudo.multiply(BigDecimal.valueOf(signo));

            AcumuladorVenta acumulador = porCondicion.computeIfAbsent(condicionVenta, k -> new AcumuladorVenta());
            acumulador.cantidad++;
            acumulador.total = acumulador.total.add(montoSignado);
            total = total.add(montoSignado);

            detalle.add(new FilaDetalleVenta(fechaEmision, tipoComprobante, consecutivo, condicionVenta,
                    facturaId, clienteId, facturaReferenciaId, moneda, montoCrudo, signo));
        }

        List<FilaVentasPorCondicion> porCondicionVenta = porCondicion.entrySet().stream()
                .map(e -> new FilaVentasPorCondicion(e.getKey(), e.getValue().cantidad, e.getValue().total))
                .toList();

        SerieVentas serie = new SerieVentas(total, filas.size(), porCondicionVenta);
        return new ResultadoVentas(serie, detalle);
    }

    /**
     * Q2 -- una sola pasada (Decisión B3), agrupando por {@code cobro_factura.medio_pago}
     * ÚNICAMENTE (Decisión D6). {@code descripcionMedioPago} falla cerrado (Decisión B6): un código
     * no reconocido aborta la generación completa del reporte, nunca se absorbe en un bucket
     * "Desconocido".
     */
    private ResultadoCobros plegarCobros(List<Object[]> filas) {
        Map<String, AcumuladorCobro> porMedio = new LinkedHashMap<>();
        List<FilaDetalleCobro> detalle = new ArrayList<>(filas.size());
        BigDecimal total = BigDecimal.ZERO;

        for (Object[] fila : filas) {
            UUID cobroId = aUuid(fila[0]);
            LocalDate fechaCobro = aFecha(fila[1]);
            String medioPago = (String) fila[2];
            BigDecimal montoCobrado = (BigDecimal) fila[3];
            String referencia = fila[4] != null ? fila[4].toString() : null;
            UUID facturaId = aUuid(fila[5]);
            String condicionVenta = (String) fila[6];
            String consecutivoFactura = fila[7] != null ? fila[7].toString() : null;

            String descripcion = descripcionMedioPago(medioPago);

            AcumuladorCobro acumulador = porMedio.computeIfAbsent(medioPago, k -> new AcumuladorCobro(descripcion));
            acumulador.cantidad++;
            acumulador.total = acumulador.total.add(montoCobrado);
            total = total.add(montoCobrado);

            detalle.add(new FilaDetalleCobro(fechaCobro, cobroId, facturaId, consecutivoFactura, condicionVenta,
                    medioPago, descripcion, referencia, montoCobrado));
        }

        List<FilaCobrosPorMedioPago> porMedioPago = porMedio.entrySet().stream()
                .map(e -> new FilaCobrosPorMedioPago(
                        e.getKey(), e.getValue().descripcion, e.getValue().cantidad, e.getValue().total))
                .toList();

        SerieCobros serie = new SerieCobros(total, filas.size(), porMedioPago);
        return new ResultadoCobros(serie, detalle);
    }

    /**
     * Q3 -- {@code total = Σ saldo_pendiente} sin redondear a piso; {@code cantidadFacturas} cuenta
     * SOLO {@code saldo_pendiente > 0} (Requisito "Fully-Credited Invoice Reports as Settled",
     * Decisiones B8/D3): {@code fechaCorte} es siempre el {@code hasta} solicitado.
     */
    private CarteraPendiente calcularCartera(LocalDate fechaCorte, List<Object[]> filas) {
        BigDecimal total = BigDecimal.ZERO;
        long cantidadFacturas = 0;
        for (Object[] fila : filas) {
            BigDecimal saldoPendiente = (BigDecimal) fila[6];
            total = total.add(saldoPendiente);
            if (saldoPendiente.signum() > 0) {
                cantidadFacturas++;
            }
        }
        return new CarteraPendiente(fechaCorte, total, cantidadFacturas);
    }

    /**
     * D4 -- comparativo del período inmediatamente anterior, mismo día-cuenta, adyacente y sin
     * solape (Decisión B4). {@code dias} usa la forma INCLUSIVA (+1) -- distinta, a propósito, de
     * la forma diferencia de {@link #validarRango} (finding 7 del diseño: son dos cantidades
     * distintas en la misma clase, no deben confundirse). El caso de febrero (desplaza a un rango
     * no-calendario de enero) es comportamiento documentado y confirmado, no un defecto.
     */
    private ComparativoPeriodoAnterior calcularComparativo(
            UUID empresaId, LocalDate desde, LocalDate hasta, BigDecimal ventasActual, BigDecimal cobrosActual) {
        long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        LocalDate hastaAnterior = desde.minusDays(1);
        LocalDate desdeAnterior = hastaAnterior.minusDays(dias - 1);

        LocalDateTime antInclusivo = desdeAnterior.atStartOfDay();
        LocalDateTime antExclusivo = hastaAnterior.plusDays(1).atStartOfDay();

        BigDecimal ventasAnterior = BigDecimal.ZERO;
        for (Object[] fila : reporteFlujoCajaRepository.sumarVentasEnPeriodoPorTipo(empresaId, antInclusivo, antExclusivo)) {
            String tipoComprobante = (String) fila[0];
            BigDecimal montoCrudo = (BigDecimal) fila[1];
            ventasAnterior = ventasAnterior.add(montoCrudo.multiply(BigDecimal.valueOf(signo(tipoComprobante))));
        }

        BigDecimal cobrosAnterior = reporteFlujoCajaRepository.sumarCobrosEnPeriodo(empresaId, antInclusivo, antExclusivo);

        BigDecimal variacionVentas = ventasActual.subtract(ventasAnterior);
        BigDecimal variacionCobros = cobrosActual.subtract(cobrosAnterior);

        return new ComparativoPeriodoAnterior(
                desdeAnterior, hastaAnterior, ventasAnterior, cobrosAnterior, variacionVentas, variacionCobros);
    }

    /**
     * Copia verbatim de {@code ReporteIvaService#validarRango}: forma DIFERENCIA
     * ({@code DAYS.between(desde, hasta) > 366}), NO +1 -- a propósito distinta de la forma
     * inclusiva de {@link #calcularComparativo} (finding 7 del diseño).
     */
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
     * Códigos verificados contra {@code TipoComprobantePerfil}: {@code 01} Factura, {@code 02} Nota
     * de Débito, {@code 03} Nota de Crédito, {@code 04} Tiquete. Un código desconocido falla cerrado
     * -- nunca se asume {@code +1} (Decisión B5).
     */
    private static int signo(String tipoComprobante) {
        return switch (tipoComprobante) {
            case "01", "02", "04" -> 1;
            case "03" -> -1;
            default -> throw new IllegalStateException("tipo_comprobante desconocido: " + tipoComprobante);
        };
    }

    /**
     * Falla cerrado ante un {@code medio_pago} que el sistema no sabe clasificar (Decisión B6,
     * resuelta por el usuario como obligatoria). NO se reusa {@code MedioPagoInvalidoException}:
     * esa mapea a 400 y esto es un problema de integridad de datos del servidor, no un dato de
     * entrada del llamador -- se envuelve la {@link IllegalArgumentException} de
     * {@link TipoMedioPago#fromCodigo} en una NUEVA {@link IllegalStateException}.
     */
    private static String descripcionMedioPago(String codigo) {
        try {
            return TipoMedioPago.fromCodigo(codigo).getDescripcion();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("medio_pago desconocido en cobro_factura: " + codigo, e);
        }
    }

    private static UUID aUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        return raw instanceof UUID u ? u : UUID.fromString(raw.toString());
    }

    private static LocalDate aFecha(Object raw) {
        if (raw instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        return LocalDate.parse(raw.toString().substring(0, 10));
    }

    private record ResultadoVentas(SerieVentas serie, List<FilaDetalleVenta> detalle) {
    }

    private record ResultadoCobros(SerieCobros serie, List<FilaDetalleCobro> detalle) {
    }

    private static final class AcumuladorVenta {
        private long cantidad;
        private BigDecimal total = BigDecimal.ZERO;
    }

    private static final class AcumuladorCobro {
        private final String descripcion;
        private long cantidad;
        private BigDecimal total = BigDecimal.ZERO;

        private AcumuladorCobro(String descripcion) {
            this.descripcion = descripcion;
        }
    }
}
