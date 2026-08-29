package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import cr.ac.fractall.facturacion.dto.CobroRegistradoResponse;
import cr.ac.fractall.facturacion.dto.HistorialCobrosResponse;
import cr.ac.fractall.facturacion.dto.RegistrarCobroRequest;
import cr.ac.fractall.facturacion.modelo.CobroFactura;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;
import cr.ac.fractall.facturacion.repositorio.CobroFacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaEstadoCobroRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba unitaria de {@link CobroFacturaService} (Release 3 / Fase C, PR3, ver diseño de
 * {@code cobro_factura}). Mockito puro, SIN contexto de Spring ni Testcontainers -- a diferencia
 * de {@code NotaCreditoDebitoServiceTest}: los triggers de V23 (autoridad real de las reglas) ya
 * quedaron probados con Postgres real en PR1/PR2 ({@code AislamientoMultiTenantTest}); esta clase
 * prueba únicamente que el pre-chequeo de Java produce el contrato HTTP correcto ANTES de llegar
 * a la base de datos.
 *
 * <p>Nota de estilo Mockito: cada mock auxiliar ({@code facturaMock}/{@code comprobanteMock}/
 * {@code estadoMock}) se construye COMPLETO en una variable local ANTES de iniciar la cadena
 * {@code when(...).thenReturn(...)} del mock que lo contiene -- encadenar la construcción dentro
 * del argumento de {@code thenReturn(...)} corrompe el estado de "stubbing en progreso" de
 * Mockito (ver {@code UnfinishedStubbingException}, hint 3: "stubbing the behaviour of another
 * mock inside before 'thenReturn' instruction is completed").
 */
@ExtendWith(MockitoExtension.class)
class CobroFacturaServiceTest {

    @Mock
    private FacturaRepository facturaRepository;

    @Mock
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Mock
    private CobroFacturaRepository cobroFacturaRepository;

    @Mock
    private FacturaEstadoCobroRepository facturaEstadoCobroRepository;

    private CobroFacturaService cobroFacturaService;

    private UUID empresaId;
    private UUID usuarioId;
    private UUID facturaId;

    @BeforeEach
    void setUp() {
        cobroFacturaService = new CobroFacturaService(
                facturaRepository, comprobanteElectronicoRepository, cobroFacturaRepository,
                facturaEstadoCobroRepository);

        empresaId = UUID.randomUUID();
        usuarioId = UUID.randomUUID();
        facturaId = UUID.randomUUID();

        TenantContext.set(empresaId);
        // 3-arg constructor: el de 2 args deja authenticated=false (ver Spring Security Javadoc),
        // que es exactamente el bug pinneado por NotaCreditoDebitoServiceTest:159.
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * {@code lenient()}: fixture compartida por casos que rechazan temprano (p.ej. alcance) y
     * nunca llegan a leer {@code getTotal()} -- sin {@code lenient()}, el modo estricto por
     * defecto de {@code MockitoExtension} marca esos stubs como {@code UnnecessaryStubbing}.
     */
    private Factura facturaMock(String condicionVenta, BigDecimal total) {
        Factura factura = mock(Factura.class);
        lenient().when(factura.getId()).thenReturn(facturaId);
        lenient().when(factura.getCondicionVenta()).thenReturn(condicionVenta);
        lenient().when(factura.getTotal()).thenReturn(total);
        return factura;
    }

    private ComprobanteElectronico comprobanteMock(String estado) {
        ComprobanteElectronico comprobante = mock(ComprobanteElectronico.class);
        lenient().when(comprobante.getEstado()).thenReturn(estado);
        return comprobante;
    }

    private RegistrarCobroRequest requestConMonto(String monto) {
        return new RegistrarCobroRequest(new BigDecimal(monto), "04", null, null);
    }

    private FacturaEstadoCobro estadoMock(BigDecimal total, BigDecimal totalNc, BigDecimal totalNeto,
            BigDecimal totalCobrado, BigDecimal saldoPendiente, String estadoCobro) {
        FacturaEstadoCobro estado = mock(FacturaEstadoCobro.class);
        when(estado.getFacturaId()).thenReturn(facturaId);
        when(estado.getTotal()).thenReturn(total);
        when(estado.getTotalNotaCredito()).thenReturn(totalNc);
        when(estado.getTotalNeto()).thenReturn(totalNeto);
        when(estado.getTotalCobrado()).thenReturn(totalCobrado);
        when(estado.getSaldoPendiente()).thenReturn(saldoPendiente);
        when(estado.getEstadoCobro()).thenReturn(estadoCobro);
        return estado;
    }

    private CobroFactura cobroMock(BigDecimal monto) {
        CobroFactura cobro = mock(CobroFactura.class);
        when(cobro.getId()).thenReturn(UUID.randomUUID());
        when(cobro.getFacturaId()).thenReturn(facturaId);
        when(cobro.getMontoCobrado()).thenReturn(monto);
        when(cobro.getFechaCobro()).thenReturn(LocalDateTime.now());
        when(cobro.getMedioPago()).thenReturn("04");
        when(cobro.getRegistradoPor()).thenReturn(usuarioId);
        when(cobro.getCreateDate()).thenReturn(LocalDateTime.now());
        return cobro;
    }

    // =========================================================================
    // registrar -- alcance (condicion_venta)
    // =========================================================================

    @Test
    void registrarSobreFacturaDeContadoLanzaFacturaNoCobrableAntesDeCualquierEscritura() {
        Factura factura = facturaMock("01", new BigDecimal("1130.00000"));
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));

        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("100.00000")))
                .isInstanceOf(FacturaNoCobrableException.class)
                .hasMessageContaining("01");

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
        verify(cobroFacturaRepository, never()).save(any());
    }

    @Test
    void registrarSobreFacturaDeArrendamientoLanzaFacturaNoCobrableParaAmbosCodigos() {
        for (String condicionVenta : List.of("05", "06")) {
            Factura factura = facturaMock(condicionVenta, new BigDecimal("1130.00000"));
            when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));

            assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("100.00000")))
                    .isInstanceOf(FacturaNoCobrableException.class)
                    .hasMessageContaining(condicionVenta);
        }

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
    }

    // =========================================================================
    // registrar -- comprobante ACEPTADO
    // =========================================================================

    @Test
    void registrarSobreComprobanteNoAceptadoLanzaFacturaOrigenNoAceptada() {
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        ComprobanteElectronico comprobante = comprobanteMock("GENERADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("100.00000")))
                .isInstanceOf(FacturaOrigenNoAceptadaException.class)
                .hasMessageContaining("GENERADO");

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
    }

    // =========================================================================
    // registrar -- medio_pago
    // =========================================================================

    @Test
    void registrarConMedioPagoInvalidoLanzaMedioPagoInvalido() {
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        ComprobanteElectronico comprobante = comprobanteMock("ACEPTADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        RegistrarCobroRequest request = new RegistrarCobroRequest(new BigDecimal("100.00000"), "88", null, null);

        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, request))
                .isInstanceOf(MedioPagoInvalidoException.class)
                .hasMessageContaining("88");

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
    }

    // =========================================================================
    // registrar -- tope (neteado contra NC ACEPTADAS)
    // =========================================================================

    @Test
    void registrarConMontoQueExcedeElSaldoNetoLanzaMontoCobroExcedeSaldo() {
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        ComprobanteElectronico comprobante = comprobanteMock("ACEPTADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.sumarTotalNotasCreditoAceptadasPorFacturaOrigen(facturaId, empresaId))
                .thenReturn(BigDecimal.ZERO);
        when(cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId)).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("1130.00001")))
                .isInstanceOf(MontoCobroExcedeSaldoException.class)
                .hasMessageContaining("1130");

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
    }

    @Test
    void registrarPorElSaldoExactoSePermiteYRetornaRespuestaCompuesta() {
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        ComprobanteElectronico comprobante = comprobanteMock("ACEPTADO");
        FacturaEstadoCobro estadoPostFlush = estadoMock(
                new BigDecimal("1130.00000"), BigDecimal.ZERO, new BigDecimal("1130.00000"),
                new BigDecimal("1130.00000"), BigDecimal.ZERO, "COBRADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.sumarTotalNotasCreditoAceptadasPorFacturaOrigen(facturaId, empresaId))
                .thenReturn(BigDecimal.ZERO);
        when(cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId)).thenReturn(BigDecimal.ZERO);
        when(facturaEstadoCobroRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(estadoPostFlush));

        CobroRegistradoResponse respuesta = cobroFacturaService.registrar(facturaId, requestConMonto("1130.00000"));

        ArgumentCaptor<CobroFactura> captor = ArgumentCaptor.forClass(CobroFactura.class);
        verify(cobroFacturaRepository).saveAndFlush(captor.capture());
        CobroFactura guardado = captor.getValue();
        assertThat(guardado.getFacturaId()).isEqualTo(facturaId);
        assertThat(guardado.getMontoCobrado()).isEqualByComparingTo("1130.00000");
        assertThat(guardado.getMedioPago()).isEqualTo("04");
        assertThat(guardado.getRegistradoPor()).isEqualTo(usuarioId);
        assertThat(guardado.getFechaCobro()).isNotNull();
        assertThat(guardado.getCreateDate()).isNotNull();

        assertThat(respuesta.cobro().facturaId()).isEqualTo(facturaId);
        assertThat(respuesta.cobro().montoCobrado()).isEqualByComparingTo("1130.00000");
        assertThat(respuesta.cobro().registradoPor()).isEqualTo(usuarioId);
        assertThat(respuesta.estado().estadoCobro()).isEqualTo("COBRADO");
        assertThat(respuesta.estado().saldoPendiente()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void registrarUnMontoMinimoPorEncimaDelSaldoExactoEsRechazado() {
        // Triangulación del límite estricto (>): mismo total que el caso anterior, un cienmilésimo
        // más allá del saldo neto -- mismo criterio pinneado en PR2 a nivel de trigger.
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        ComprobanteElectronico comprobante = comprobanteMock("ACEPTADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.sumarTotalNotasCreditoAceptadasPorFacturaOrigen(facturaId, empresaId))
                .thenReturn(BigDecimal.ZERO);
        when(cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId)).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("1130.00001")))
                .isInstanceOf(MontoCobroExcedeSaldoException.class);

        verify(cobroFacturaRepository, never()).saveAndFlush(any());
    }

    /**
     * Task 3.9 -- cierra el requisito del spec "el chequeo de Java y el de SQL DEBEN coincidir en
     * la misma fixture". Mismos números EXACTOS que {@code AislamientoMultiTenantTest
     * #notaCreditoAceptadaReduceElTopeDeCobroYElSaldoDeLaVista} (PR2, ya probado contra Postgres
     * real): origen total 1130.00000, NC ACEPTADA tipo '03' total 130.00000 =&gt; saldo neto
     * 1000.00000. PR2 probó que la VISTA reporta {@code total_neto=1000} para esta fixture; esta
     * prueba prueba que el pre-chequeo de JAVA calcula el mismo 1000.00000 -- pinneado en el
     * límite estricto (1000 aceptado, 1000.00001 rechazado), sin acceso a Postgres en esta clase
     * (Mockito puro). Mismos números, misma frontera, dos motores distintos -- eso es la paridad
     * que exige el spec.
     */
    @Test
    void elNeteoDeJavaCoincideConElDeLaVistaParaLaMismaFixtureDePr2() {
        BigDecimal totalOrigen = new BigDecimal("1130.00000");
        BigDecimal totalNcAceptada = new BigDecimal("130.00000");
        BigDecimal netoEsperado = new BigDecimal("1000.00000"); // mismo valor que reportó la vista en PR2

        Factura factura = facturaMock("02", totalOrigen);
        ComprobanteElectronico comprobante = comprobanteMock("ACEPTADO");
        FacturaEstadoCobro estadoPostFlush =
                estadoMock(totalOrigen, totalNcAceptada, netoEsperado, netoEsperado, BigDecimal.ZERO, "COBRADO");
        when(facturaRepository.findWithLockById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.sumarTotalNotasCreditoAceptadasPorFacturaOrigen(facturaId, empresaId))
                .thenReturn(totalNcAceptada);
        when(cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId)).thenReturn(BigDecimal.ZERO);
        when(facturaEstadoCobroRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(estadoPostFlush));

        // El cobro EXACTO al neto esperado (1000.00000) debe ser aceptado por el pre-chequeo de
        // Java -- si Java calculara un neto distinto de 1000, este cobro sería rechazado.
        CobroRegistradoResponse respuesta = cobroFacturaService.registrar(facturaId, requestConMonto("1000.00000"));
        assertThat(respuesta.estado().totalNeto()).isEqualByComparingTo(netoEsperado);

        // Un cobro adicional de 0.00001 sobre lo ya cobrado debe rechazarse -- pinnea el límite
        // superior del neto de Java exactamente en 1000.00000, igual que la vista en PR2.
        when(cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId)).thenReturn(netoEsperado);
        assertThatThrownBy(() -> cobroFacturaService.registrar(facturaId, requestConMonto("0.00001")))
                .isInstanceOf(MontoCobroExcedeSaldoException.class);
    }

    // =========================================================================
    // listar
    // =========================================================================

    @Test
    void listarRetornaHistorialOrdenadoYElEstadoActual() {
        Factura factura = facturaMock("02", new BigDecimal("1130.00000"));
        CobroFactura cobro500 = cobroMock(new BigDecimal("500.00000"));
        CobroFactura cobro600 = cobroMock(new BigDecimal("600.00000"));
        FacturaEstadoCobro estado = estadoMock(
                new BigDecimal("1130.00000"), BigDecimal.ZERO, new BigDecimal("1130.00000"),
                new BigDecimal("1100.00000"), new BigDecimal("30.00000"), "PARCIAL");
        when(facturaRepository.findById(facturaId)).thenReturn(Optional.of(factura));
        when(cobroFacturaRepository.findByFacturaIdOrderByFechaCobroAscIdAsc(facturaId))
                .thenReturn(List.of(cobro500, cobro600));
        when(facturaEstadoCobroRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(estado));

        HistorialCobrosResponse respuesta = cobroFacturaService.listar(facturaId);

        assertThat(respuesta.cobros()).hasSize(2);
        assertThat(respuesta.cobros().get(0).montoCobrado()).isEqualByComparingTo("500.00000");
        assertThat(respuesta.cobros().get(1).montoCobrado()).isEqualByComparingTo("600.00000");
        assertThat(respuesta.estado().estadoCobro()).isEqualTo("PARCIAL");
        assertThat(respuesta.estado().saldoPendiente()).isEqualByComparingTo("30.00000");

        verify(facturaRepository, never()).findWithLockById(any());
    }

    @Test
    void listarSobreFacturaInexistenteLanzaFacturaNoEncontrada() {
        when(facturaRepository.findById(facturaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cobroFacturaService.listar(facturaId))
                .isInstanceOf(FacturaNoEncontradaException.class);
    }

    @Test
    void listarSobreFacturaFueraDeAlcanceLanzaFacturaNoCobrableDistintoDeNoEncontrada() {
        Factura factura = facturaMock("01", new BigDecimal("1130.00000"));
        when(facturaRepository.findById(facturaId)).thenReturn(Optional.of(factura));

        assertThatThrownBy(() -> cobroFacturaService.listar(facturaId))
                .isInstanceOf(FacturaNoCobrableException.class)
                .isNotInstanceOf(FacturaNoEncontradaException.class);
    }
}
