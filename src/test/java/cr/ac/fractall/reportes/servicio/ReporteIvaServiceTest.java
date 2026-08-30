package cr.ac.fractall.reportes.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.reportes.dto.FilaResumenIva;
import cr.ac.fractall.reportes.dto.ReporteIvaResponse;
import cr.ac.fractall.reportes.repositorio.FilaLineaComprobante;
import cr.ac.fractall.reportes.repositorio.ReporteIvaRepository;

/**
 * Prueba unitaria de {@link ReporteIvaService} (Release 3 / Fase D, PR3, ver el diseño). Mockito
 * puro sobre {@link ReporteIvaRepository} e {@link ImpuestoLineaExoneracionRepository}, SIN base
 * de datos -- mismo criterio que {@code CalculadoraImpuestoLineaTest}: prueba el traversal
 * (signo, agrupación por tarifa, validación de rango) en aislamiento; la forma real del fetch
 * (Q1/Q2, tenant, join) queda probada contra Postgres real por {@code ReporteIvaIT} en PR4.
 */
@ExtendWith(MockitoExtension.class)
class ReporteIvaServiceTest {

    private static final String ESTADO_ACEPTADO = "ACEPTADO";

    @Mock
    private ReporteIvaRepository reporteIvaRepository;

    @Mock
    private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;

    private ReporteIvaService reporteIvaService;

    private final LocalDate desde = LocalDate.of(2026, 8, 1);
    private final LocalDate hasta = LocalDate.of(2026, 8, 31);

    private void construir() {
        reporteIvaService = new ReporteIvaService(reporteIvaRepository, impuestoLineaExoneracionRepository);
    }

    private void stubSinLineas() {
        // Sin exoneraciones inline en ningun escenario de este archivo: la lista de lineaIds
        // pasada varia por test, así que se acepta cualquier Collection<UUID>.
        when(impuestoLineaExoneracionRepository.findByLineaIdIn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    private FilaLineaComprobante fila(
            String tipoComprobante, UUID comprobanteId, BigDecimal subtotal,
            boolean gravado, BigDecimal porcentaje) {
        return new FilaLineaComprobante(
                comprobanteId, tipoComprobante, "00100001010000000001", "clave-" + comprobanteId,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                UUID.randomUUID(), UUID.randomUUID(), "CRC", null,
                UUID.randomUUID(), 1, subtotal,
                gravado, porcentaje,
                null, null);
    }

    @Test
    void facturaTiqueteYNotaDebitoSumanPositivo() {
        construir();
        stubSinLineas();
        BigDecimal subtotal = new BigDecimal("1000.00000");
        BigDecimal porcentaje = new BigDecimal("13.00");
        List<FilaLineaComprobante> filas = List.of(
                fila("01", UUID.randomUUID(), subtotal, true, porcentaje),
                fila("04", UUID.randomUUID(), subtotal, true, porcentaje),
                fila("02", UUID.randomUUID(), subtotal, true, porcentaje));
        when(reporteIvaRepository.buscarLineasEnPeriodo(
                ESTADO_ACEPTADO, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                .thenReturn(filas);

        ReporteIvaResponse respuesta = reporteIvaService.generar(desde, hasta);

        // 3 lineas * (1000 * 13% = 130.00000) = 390.00000, todas suman positivo.
        assertThat(respuesta.totalDebitoFiscal()).isEqualByComparingTo("390.00000");
        assertThat(respuesta.resumen()).hasSize(1);
        assertThat(respuesta.resumen().get(0).impuestoNeto()).isEqualByComparingTo("390.00000");
    }

    @Test
    void notaCreditoRestaDelTotal() {
        construir();
        stubSinLineas();
        BigDecimal porcentaje = new BigDecimal("13.00");
        List<FilaLineaComprobante> filas = List.of(
                fila("01", UUID.randomUUID(), new BigDecimal("1000.00000"), true, porcentaje),
                fila("03", UUID.randomUUID(), new BigDecimal("500.00000"), true, porcentaje));
        when(reporteIvaRepository.buscarLineasEnPeriodo(
                ESTADO_ACEPTADO, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                .thenReturn(filas);

        ReporteIvaResponse respuesta = reporteIvaService.generar(desde, hasta);

        // Factura: +130.00000; NC: -65.00000 -> total 65.00000.
        assertThat(respuesta.totalDebitoFiscal()).isEqualByComparingTo("65.00000");
        assertThat(respuesta.resumen()).hasSize(1);
        assertThat(respuesta.resumen().get(0).impuestoNeto()).isEqualByComparingTo("65.00000");
    }

    @Test
    void tipoComprobanteDesconocidoLanzaIllegalStateException() {
        construir();
        stubSinLineas();
        List<FilaLineaComprobante> filas = List.of(
                fila("09", UUID.randomUUID(), new BigDecimal("1000.00000"), true, new BigDecimal("13.00")));
        when(reporteIvaRepository.buscarLineasEnPeriodo(
                ESTADO_ACEPTADO, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                .thenReturn(filas);

        assertThatThrownBy(() -> reporteIvaService.generar(desde, hasta))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("09");
    }

    @Test
    void lineasDeDistintosComprobantesMismaTarifaSeAgrupan() {
        construir();
        stubSinLineas();
        BigDecimal porcentaje = new BigDecimal("13.00");
        List<FilaLineaComprobante> filas = List.of(
                fila("01", UUID.randomUUID(), new BigDecimal("1000.00000"), true, porcentaje),
                fila("01", UUID.randomUUID(), new BigDecimal("2000.00000"), true, porcentaje));
        when(reporteIvaRepository.buscarLineasEnPeriodo(
                ESTADO_ACEPTADO, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                .thenReturn(filas);

        ReporteIvaResponse respuesta = reporteIvaService.generar(desde, hasta);

        // (1000+2000) * 13% = 390.00000, una sola fila de resumen.
        assertThat(respuesta.resumen()).hasSize(1);
        FilaResumenIva unica = respuesta.resumen().get(0);
        assertThat(unica.baseImponible()).isEqualByComparingTo("3000.00000");
        assertThat(unica.impuestoNeto()).isEqualByComparingTo("390.00000");
    }

    @Test
    void porcentajeConDistintaEscalaSeNormalizaEnUnaSolaTarifa() {
        construir();
        stubSinLineas();
        List<FilaLineaComprobante> filas = List.of(
                fila("01", UUID.randomUUID(), new BigDecimal("1000.00000"), true, new BigDecimal("13.0")),
                fila("01", UUID.randomUUID(), new BigDecimal("1000.00000"), true, new BigDecimal("13.00")));
        when(reporteIvaRepository.buscarLineasEnPeriodo(
                ESTADO_ACEPTADO, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay()))
                .thenReturn(filas);

        ReporteIvaResponse respuesta = reporteIvaService.generar(desde, hasta);

        // 13.0 y 13.00 deben normalizar a UNA sola tarifa, no dos (BigDecimal.equals es sensible
        // a escala).
        assertThat(respuesta.resumen()).hasSize(1);
        assertThat(respuesta.resumen().get(0).impuestoNeto()).isEqualByComparingTo("260.00000");
    }

    @Test
    void hastaAntesDeDesdeLanzaRangoFechasInvalidaException() {
        construir();
        LocalDate desdeInvalido = LocalDate.of(2026, 6, 1);
        LocalDate hastaInvalida = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() -> reporteIvaService.generar(desdeInvalido, hastaInvalida))
                .isInstanceOf(RangoFechasInvalidaException.class);
    }

    @Test
    void rangoMayorA366DiasLanzaRangoFechasInvalidaException() {
        construir();
        // 2024 es bisiesto: 2024-01-01 -> 2025-01-02 son 367 dias, uno mas del tope.
        LocalDate desdeAmplio = LocalDate.of(2024, 1, 1);
        LocalDate hastaAmplia = LocalDate.of(2025, 1, 2);

        assertThatThrownBy(() -> reporteIvaService.generar(desdeAmplio, hastaAmplia))
                .isInstanceOf(RangoFechasInvalidaException.class);
    }
}
