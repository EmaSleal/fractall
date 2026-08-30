package cr.ac.fractall.reportes.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.reportes.dto.ReporteFlujoCajaResponse;
import cr.ac.fractall.reportes.repositorio.ReporteFlujoCajaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba unitaria de {@link ReporteFlujoCajaService} (Release 3 / Fase D, PR4 de 7, ver el diseño
 * obs #918). Mockito puro sobre {@link ReporteFlujoCajaRepository}, SIN base de datos -- mismo
 * criterio que {@code ReporteIvaServiceTest}: prueba el traversal (signo, agrupación, validación
 * de rango, comparativo de período anterior) en aislamiento; la forma real del fetch (Q1-Q5,
 * tenant, join) queda probada contra Postgres real por {@code ReporteFlujoCajaRepositoryIT}
 * (PR2/PR3, ya entregadas).
 */
@ExtendWith(MockitoExtension.class)
class ReporteFlujoCajaServiceTest {

    @Mock
    private ReporteFlujoCajaRepository reporteFlujoCajaRepository;

    private ReporteFlujoCajaService reporteFlujoCajaService;

    private final UUID empresaId = UUID.randomUUID();
    private final LocalDate desde = LocalDate.of(2026, 8, 1);
    private final LocalDate hasta = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        TenantContext.set(empresaId);
        reporteFlujoCajaService = new ReporteFlujoCajaService(reporteFlujoCajaRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Q5 devuelve {@code BigDecimal} escalar directo (no {@code List}), así que el default de
     * Mockito es {@code null} -- sin este stub, cualquier prueba que llegue al comparativo (D4)
     * revienta con {@code NullPointerException} en {@code BigDecimal.subtract}, aunque esa prueba
     * no esté verificando el comparativo en sí.
     */
    private void stubComparativoAnteriorEnCero() {
        stubCobrosAnterior(BigDecimal.ZERO);
    }

    private void stubCobrosAnterior(BigDecimal monto) {
        when(reporteFlujoCajaRepository.sumarCobrosEnPeriodo(eq(empresaId), any(), any()))
                .thenReturn(monto);
    }

    private void stubVentasAnteriorPorTipo(Object[]... filas) {
        when(reporteFlujoCajaRepository.sumarVentasEnPeriodoPorTipo(eq(empresaId), any(), any()))
                .thenReturn(List.of(filas));
    }

    private void stubVentas(Object[]... filas) {
        when(reporteFlujoCajaRepository.buscarVentasEnPeriodo(eq(empresaId), any(), any()))
                .thenReturn(List.of(filas));
    }

    private void stubCobros(Object[]... filas) {
        when(reporteFlujoCajaRepository.buscarCobrosEnPeriodo(eq(empresaId), any(), any()))
                .thenReturn(List.of(filas));
    }

    private void stubCartera(Object[]... filas) {
        when(reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(eq(empresaId), any()))
                .thenReturn(List.of(filas));
    }

    /** Orden posicional de Q1 -- ver {@code ReporteFlujoCajaRepository#buscarVentasEnPeriodo}. */
    private Object[] filaVenta(
            String tipoComprobante, String condicionVenta, BigDecimal total, LocalDateTime fechaEmision) {
        return new Object[] {
            UUID.randomUUID(), tipoComprobante, "cons-" + UUID.randomUUID(), fechaEmision,
            condicionVenta, UUID.randomUUID(), "CRC", null, total
        };
    }

    /** Orden posicional de Q2 -- ver {@code ReporteFlujoCajaRepository#buscarCobrosEnPeriodo}. */
    private Object[] filaCobro(String medioPago, BigDecimal montoCobrado, LocalDateTime fechaCobro) {
        return new Object[] {
            UUID.randomUUID(), fechaCobro, medioPago, montoCobrado, "ref", UUID.randomUUID(), "02", "cons-001"
        };
    }

    /** Orden posicional de Q3 -- ver {@code ReporteFlujoCajaRepository#buscarCarteraPendienteAlCorte}. */
    private Object[] filaCartera(BigDecimal saldoPendiente) {
        return new Object[] {
            UUID.randomUUID(), "cons-001", new BigDecimal("1000.00000"), BigDecimal.ZERO,
            new BigDecimal("1000.00000"), BigDecimal.ZERO, saldoPendiente
        };
    }

    @Test
    void hastaAnteriorADesdeLanzaRangoFechasInvalida() {
        LocalDate desde = LocalDate.of(2026, 6, 1);
        LocalDate hasta = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() -> reporteFlujoCajaService.generar(desde, hasta))
                .isInstanceOf(RangoFechasInvalidaException.class);
    }

    @Test
    void rangoMayorAlMaximoLanzaRangoFechasInvalida() {
        // 2024 es bisiesto: 2024-01-01 -> 2025-01-02 son 367 dias, uno mas del tope.
        LocalDate desdeAmplio = LocalDate.of(2024, 1, 1);
        LocalDate hastaAmplia = LocalDate.of(2025, 1, 2);

        assertThatThrownBy(() -> reporteFlujoCajaService.generar(desdeAmplio, hastaAmplia))
                .isInstanceOf(RangoFechasInvalidaException.class);
    }

    @Test
    void ventasIncluyenCondicionVentaContado() {
        stubComparativoAnteriorEnCero();
        stubVentas(filaVenta("01", "01", new BigDecimal("1130.00000"), LocalDateTime.of(2026, 8, 3, 8, 0)));
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.ventas().total()).isEqualByComparingTo("1130.00000");
        assertThat(respuesta.ventas().porCondicionVenta()).hasSize(1);
        assertThat(respuesta.ventas().porCondicionVenta().get(0).condicionVenta()).isEqualTo("01");
        assertThat(respuesta.detalleVentas()).hasSize(1);
    }

    @Test
    void notaCreditoRestaDelBucketDeSuCondicionVenta() {
        stubComparativoAnteriorEnCero();
        stubVentas(
                filaVenta("01", "02", new BigDecimal("1000.00000"), LocalDateTime.of(2026, 8, 4, 9, 0)),
                filaVenta("03", "02", new BigDecimal("300.00000"), LocalDateTime.of(2026, 8, 18, 11, 0)));
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        // Factura +1000, NC -300 -> bucket '02' = 700, ambas en el MISMO bucket (heredado).
        assertThat(respuesta.ventas().porCondicionVenta()).hasSize(1);
        assertThat(respuesta.ventas().porCondicionVenta().get(0).total()).isEqualByComparingTo("700.00000");
        assertThat(respuesta.ventas().total()).isEqualByComparingTo("700.00000");
    }

    @Test
    void tipoComprobanteDesconocidoHaceFallarLaSerieDeVentas() {
        stubVentas(filaVenta("09", "01", new BigDecimal("1000.00000"), LocalDateTime.of(2026, 8, 5, 10, 0)));

        assertThatThrownBy(() -> reporteFlujoCajaService.generar(desde, hasta))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("09");
    }

    @Test
    void medioPagoConocidoSeReportaConSuCodigoYSuDescripcion() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros(filaCobro("04", new BigDecimal("500.00000"), LocalDateTime.of(2026, 8, 10, 12, 0)));
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.cobros().total()).isEqualByComparingTo("500.00000");
        assertThat(respuesta.cobros().porMedioPago()).hasSize(1);
        assertThat(respuesta.cobros().porMedioPago().get(0).medioPago()).isEqualTo("04");
        assertThat(respuesta.cobros().porMedioPago().get(0).descripcionMedioPago())
                .isEqualTo("Transferencia - depósito bancario");
        assertThat(respuesta.detalleCobros()).hasSize(1);
        assertThat(respuesta.detalleCobros().get(0).descripcionMedioPago())
                .isEqualTo("Transferencia - depósito bancario");
    }

    @Test
    void medioPagoDesconocidoHaceFallarElReporte() {
        stubVentas();
        stubCobros(filaCobro("77", new BigDecimal("500.00000"), LocalDateTime.of(2026, 8, 10, 12, 0)));

        assertThatThrownBy(() -> reporteFlujoCajaService.generar(desde, hasta))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("77");
    }

    @Test
    void carteraCuentaSoloFacturasConSaldoPositivo() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros();
        stubCartera(
                filaCartera(new BigDecimal("500.00000")),
                filaCartera(BigDecimal.ZERO),
                filaCartera(new BigDecimal("-0.00001")));

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        // Total suma las 3 filas SIN piso (500 + 0 - 0.00001), pero cantidadFacturas cuenta SOLO
        // la de saldo > 0 -- la totalmente acreditada (0) y la sobre-cobrada no cuentan.
        assertThat(respuesta.cartera().total()).isEqualByComparingTo("499.99999");
        assertThat(respuesta.cartera().cantidadFacturas()).isEqualTo(1);
        assertThat(respuesta.cartera().fechaCorte()).isEqualTo(hasta);
    }

    @Test
    void periodoAnteriorDeAgostoCompletoEsJulioCompleto() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(respuesta.comparativo().desdeAnterior()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(respuesta.comparativo().hastaAnterior()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    /**
     * Caso de febrero (D4, confirmado por el usuario, no un defecto): día-cuenta puro, NO
     * calendario -- el período anterior de un febrero completo (28 días en 2026, no bisiesto) cae
     * en un rango de enero que NO empieza el día 1.
     */
    @Test
    void periodoAnteriorDeFebreroCompletoEsDelCuatroAlTreintaYUnoDeEnero() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(respuesta.comparativo().desdeAnterior()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(respuesta.comparativo().hastaAnterior()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void periodoAnteriorEsAdyacenteYNoSeSolapaConElPeriodoActual() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros();
        stubCartera();
        LocalDate desdeActual = LocalDate.of(2026, 3, 10);
        LocalDate hastaActual = LocalDate.of(2026, 3, 20);

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desdeActual, hastaActual);

        // Adyacente: el día siguiente al hastaAnterior es exactamente el desde solicitado.
        assertThat(respuesta.comparativo().hastaAnterior().plusDays(1)).isEqualTo(desdeActual);
        // Sin solape: el hastaAnterior es estrictamente anterior al desde solicitado.
        assertThat(respuesta.comparativo().hastaAnterior()).isBefore(desdeActual);
    }

    @Test
    void variacionEsDeltaAbsolutoNoPorcentaje() {
        stubVentas();
        stubCobros();
        stubCartera();
        stubVentasAnteriorPorTipo(new Object[] {"01", new BigDecimal("400.00000")});
        stubCobrosAnterior(new BigDecimal("100.00000"));

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        // Ventas actuales = 0 (sin filas), anterior = 400 -> delta ABSOLUTO -400, nunca -100%.
        assertThat(respuesta.comparativo().variacionVentas()).isEqualByComparingTo("-400.00000");
        assertThat(respuesta.comparativo().variacionCobros()).isEqualByComparingTo("-100.00000");
    }

    @Test
    void variacionConPeriodoAnteriorEnCeroEsElTotalActual() {
        stubComparativoAnteriorEnCero();
        stubVentas(filaVenta("01", "01", new BigDecimal("1000.00000"), LocalDateTime.of(2026, 8, 5, 10, 0)));
        stubCobros(filaCobro("01", new BigDecimal("250.00000"), LocalDateTime.of(2026, 8, 6, 10, 0)));
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.comparativo().variacionVentas()).isEqualByComparingTo("1000.00000");
        assertThat(respuesta.comparativo().variacionCobros()).isEqualByComparingTo("250.00000");
    }

    /**
     * Decisión B3 -- una sola pasada: {@code detalleVentas} debe conservar CADA fila de Q1 tal
     * cual, sin agregar, con el mismo orden de llegada (no una lista reconstruida por bucket).
     */
    @Test
    void detalleVentasConservaCadaFilaSinAgregar() {
        stubComparativoAnteriorEnCero();
        stubVentas(
                filaVenta("01", "01", new BigDecimal("100.00000"), LocalDateTime.of(2026, 8, 2, 9, 0)),
                filaVenta("04", "02", new BigDecimal("200.00000"), LocalDateTime.of(2026, 8, 3, 10, 0)));
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.detalleVentas()).hasSize(2);
        assertThat(respuesta.detalleVentas().get(0).total()).isEqualByComparingTo("100.00000");
        assertThat(respuesta.detalleVentas().get(0).signo()).isEqualTo(1);
        assertThat(respuesta.detalleVentas().get(1).total()).isEqualByComparingTo("200.00000");
        assertThat(respuesta.detalleVentas().get(1).condicionVenta()).isEqualTo("02");
    }

    @Test
    void detalleCobrosConservaCadaFilaSinAgregar() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros(
                filaCobro("01", new BigDecimal("100.00000"), LocalDateTime.of(2026, 8, 2, 9, 0)),
                filaCobro("02", new BigDecimal("200.00000"), LocalDateTime.of(2026, 8, 3, 10, 0)));
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.detalleCobros()).hasSize(2);
        assertThat(respuesta.detalleCobros().get(0).montoCobrado()).isEqualByComparingTo("100.00000");
        assertThat(respuesta.detalleCobros().get(1).montoCobrado()).isEqualByComparingTo("200.00000");
    }

    @Test
    void ventasConVariasCondicionVentaProducenVariosBuckets() {
        stubComparativoAnteriorEnCero();
        stubVentas(
                filaVenta("01", "01", new BigDecimal("500.00000"), LocalDateTime.of(2026, 8, 2, 9, 0)),
                filaVenta("01", "02", new BigDecimal("700.00000"), LocalDateTime.of(2026, 8, 3, 10, 0)));
        stubCobros();
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.ventas().porCondicionVenta()).hasSize(2);
        assertThat(respuesta.ventas().total()).isEqualByComparingTo("1200.00000");
        assertThat(respuesta.ventas().cantidadComprobantes()).isEqualTo(2);
    }

    /**
     * Requisito "Cobros Series Groups by cobro_factura.medio_pago Only" (D6): la agrupación es por
     * {@code cobro_factura.medio_pago} EXCLUSIVAMENTE -- dos cobros con el mismo medio de pago
     * agrupan juntos sin importar la {@code condicion_venta} de sus facturas respectivas.
     */
    @Test
    void cobrosAgrupanPorMedioPagoConVariasFilasDelMismoMedio() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros(
                filaCobro("02", new BigDecimal("300.00000"), LocalDateTime.of(2026, 8, 2, 9, 0)),
                filaCobro("02", new BigDecimal("150.00000"), LocalDateTime.of(2026, 8, 5, 9, 0)));
        stubCartera();

        ReporteFlujoCajaResponse respuesta = reporteFlujoCajaService.generar(desde, hasta);

        assertThat(respuesta.cobros().porMedioPago()).hasSize(1);
        assertThat(respuesta.cobros().porMedioPago().get(0).cantidadCobros()).isEqualTo(2);
        assertThat(respuesta.cobros().porMedioPago().get(0).total()).isEqualByComparingTo("450.00000");
    }

    /**
     * Frontera EXACTA de {@code validarRango} (finding 7 del diseño: forma DIFERENCIA, no +1):
     * un rango de exactamente 366 días de diferencia es VÁLIDO, solo 367 excede el tope.
     */
    @Test
    void rangoDeExactamente366DiasDeDiferenciaNoLanzaExcepcion() {
        stubComparativoAnteriorEnCero();
        stubVentas();
        stubCobros();
        stubCartera();
        LocalDate desdeLimite = LocalDate.of(2024, 1, 1);
        LocalDate hastaLimite = LocalDate.of(2025, 1, 1);

        assertThat(reporteFlujoCajaService.generar(desdeLimite, hastaLimite).desde()).isEqualTo(desdeLimite);
    }
}
