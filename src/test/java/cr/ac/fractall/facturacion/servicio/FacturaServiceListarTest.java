package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.FacturaResumenResponse;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.shared.PaginaResponse;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba unitaria de {@link FacturaService#listar} y {@link FacturaService#obtener} con
 * repositorios mockeados (sin contexto de Spring, sin base de datos real).
 * Cubre paginación keyset, filtros, y manejo de errores. Spec: FR-1, FR-2, NFR-3, NFR-4.
 */
class FacturaServiceListarTest {

    /** Empresa de prueba fija para {@code TenantContext} — evita valores nulos en buscarNativo. */
    private static final UUID EMPRESA_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private FacturaRepository facturaRepository;
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private LineaFacturaRepository lineaFacturaRepository;
    private LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private LineaDescuentoRepository lineaDescuentoRepository;
    private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private FacturaMedioPagoRepository facturaMedioPagoRepository;
    private FacturaService facturaService;

    @BeforeEach
    void configurar() {
        TenantContext.set(EMPRESA_ID);

        facturaRepository = mock(FacturaRepository.class);
        comprobanteElectronicoRepository = mock(ComprobanteElectronicoRepository.class);
        lineaFacturaRepository = mock(LineaFacturaRepository.class);
        lineaCodigoComercialRepository = mock(LineaCodigoComercialRepository.class);
        lineaDescuentoRepository = mock(LineaDescuentoRepository.class);
        impuestoLineaExoneracionRepository = mock(ImpuestoLineaExoneracionRepository.class);
        facturaOtrosCargosRepository = mock(FacturaOtrosCargosRepository.class);
        facturaInformacionReferenciaRepository = mock(FacturaInformacionReferenciaRepository.class);
        facturaMedioPagoRepository = mock(FacturaMedioPagoRepository.class);

        facturaService = new FacturaService(
                mock(ClienteRepository.class),
                mock(EmpresaRepository.class),
                facturaRepository,
                lineaFacturaRepository,
                comprobanteElectronicoRepository,
                lineaCodigoComercialRepository,
                lineaDescuentoRepository,
                impuestoLineaExoneracionRepository,
                facturaOtrosCargosRepository,
                facturaInformacionReferenciaRepository,
                facturaMedioPagoRepository,
                mock(LineaFacturaEnsamblador.class),
                mock(ComprobanteEmisionService.class),
                mock(HaciendaApiService.class),
                mock(CondicionesComercialesService.class));
    }

    @AfterEach
    void limpiarTenantContext() {
        TenantContext.clear();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Crea una fila {@code Object[]} compatible con la proyección de {@code buscarNativo}.
     * Orden: id, consecutivo, tipo_comprobante, cliente_id, nombre, ambiente_hacienda, moneda,
     * total, estado, ultimo_resultado_consulta, fecha_emision (como String ISO).
     */
    private static Object[] filaResumen(int index) {
        return new Object[] {
            UUID.fromString(String.format("00000000-0000-0000-0001-%012d", index)),
            "FE-001-00100001500300000" + index,
            "01",
            UUID.randomUUID(),
            "Cliente " + index,
            "01",
            "CRC",
            new BigDecimal("1000.00"),
            "GENERADO",
            null,
            "2025-01-" + String.format("%02d", index % 28 + 1)
        };
    }

    private static List<Object[]> generarFilas(int cantidad) {
        List<Object[]> lista = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            lista.add(filaResumen(i));
        }
        return lista;
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field campo = cr.ac.fractall.shared.EntidadBase.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(entity, id);
    }

    private static Factura facturaConId(UUID id) throws Exception {
        Factura f = new Factura();
        setId(f, id);
        f.setClienteId(UUID.randomUUID());
        f.setMoneda("CRC");
        f.setSubtotal(BigDecimal.TEN);
        f.setTotalImpuesto(BigDecimal.ONE);
        f.setTotal(new BigDecimal("11.00"));
        f.setCondicionVenta("01");
        f.setMedioPago("01");
        f.setTipoCambio(new BigDecimal("1.00000"));
        f.setTotalIvaDevuelto(BigDecimal.ZERO);
        f.setCreateDate(LocalDateTime.now());
        f.setUpdateDate(LocalDateTime.now());
        return f;
    }

    private static ComprobanteElectronico comprobanteParaFactura(UUID facturaId) throws Exception {
        ComprobanteElectronico ce = new ComprobanteElectronico();
        setId(ce, UUID.randomUUID());
        ce.setFacturaId(facturaId);
        ce.setAmbienteHacienda("01");
        ce.setTipoComprobante("01");
        ce.setConsecutivo("FE-001-001000015003000001");
        ce.setClaveNumerica("50601011234567890112345678901011234567800100000001");
        ce.setEstado("GENERADO");
        ce.setIntentosEnvio(0);
        ce.setFechaEmision(LocalDateTime.now());
        return ce;
    }

    // =========================================================================
    // listar() tests — FR-1
    // =========================================================================

    @Test
    void listarConMenosItemsQueLimitRetornaNextCursorNull() {
        List<Object[]> cincoFilas = generarFilas(5);
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(cincoFilas);

        PaginaResponse<FacturaResumenResponse> respuesta =
                facturaService.listar(null, null, null, null, null, null, 20);

        assertThat(respuesta.items()).hasSize(5);
        assertThat(respuesta.nextCursor()).isNull();
    }

    @Test
    void listarConLimitMasUnoItemsSliceYRetornaNextCursor() {
        List<Object[]> veintiUnaFilas = generarFilas(21);
        // The 20th row id (index 19 in the list) becomes nextCursor
        UUID idDelItem20 = (UUID) veintiUnaFilas.get(19)[0];
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(veintiUnaFilas);

        PaginaResponse<FacturaResumenResponse> respuesta =
                facturaService.listar(null, null, null, null, null, null, 20);

        assertThat(respuesta.items()).hasSize(20);
        assertThat(respuesta.nextCursor()).isEqualTo(idDelItem20);
    }

    @Test
    void listarVerificaQueBuscarRecibeLimit1MasQueElSolicitado() {
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(11)))
                .thenReturn(generarFilas(5));

        facturaService.listar(null, null, null, null, null, null, 10);

        verify(facturaRepository).buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    void listarConClienteIdFiltroForwardea() {
        UUID clienteId = UUID.randomUUID();
        String clienteIdStr = clienteId.toString();
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), eq(clienteIdStr), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(generarFilas(1));

        facturaService.listar(null, clienteId, null, null, null, null, 20);

        verify(facturaRepository).buscarNativo(eq(EMPRESA_ID), isNull(), eq(clienteIdStr), isNull(), isNull(), isNull(), isNull(), eq(21));
    }

    @Test
    void listarConDesdeFiltroForwardea() {
        LocalDate desde = LocalDate.of(2025, 1, 1);
        LocalDate hasta = LocalDate.of(2025, 1, 31);
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), eq("2025-01-01"), eq("2025-01-31"), isNull(), isNull(), eq(21)))
                .thenReturn(generarFilas(2));

        facturaService.listar(null, null, desde, hasta, null, null, 20);

        verify(facturaRepository).buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), eq("2025-01-01"), eq("2025-01-31"), isNull(), isNull(), eq(21));
    }

    @Test
    void listarConEstadoFiltroForwardea() {
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), eq("ACEPTADO"), isNull(), eq(21)))
                .thenReturn(generarFilas(3));

        facturaService.listar(null, null, null, null, "ACEPTADO", null, 20);

        verify(facturaRepository).buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), eq("ACEPTADO"), isNull(), eq(21));
    }

    @Test
    void listarConTipoComprobanteFiltroForwardea() {
        when(facturaRepository.buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq("03"), eq(21)))
                .thenReturn(generarFilas(2));

        PaginaResponse<FacturaResumenResponse> respuesta =
                facturaService.listar(null, null, null, null, null, "03", 20);

        verify(facturaRepository).buscarNativo(eq(EMPRESA_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq("03"), eq(21));
        assertThat(respuesta.items()).allSatisfy(item -> assertThat(item.tipoComprobante()).isEqualTo("01"));
    }

    // =========================================================================
    // obtener() tests — FR-2
    // =========================================================================

    @Test
    void obtenerConIdExistenteRetornaFacturaResponse() throws Exception {
        UUID id = UUID.randomUUID();
        Factura factura = facturaConId(id);
        ComprobanteElectronico comprobante = comprobanteParaFactura(id);

        when(facturaRepository.findById(id)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(id)).thenReturn(Optional.of(comprobante));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(id)).thenReturn(List.of());
        when(facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(id)).thenReturn(List.of());
        when(facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(id)).thenReturn(List.of());
        when(facturaMedioPagoRepository.findByFacturaIdOrderByOrden(id)).thenReturn(List.of());
        when(facturaRepository.findClienteNombreByFacturaId(eq(id), any())).thenReturn(Optional.of("Cliente Test"));

        FacturaResponse respuesta = facturaService.obtener(id);

        assertThat(respuesta).isNotNull();
        assertThat(respuesta.id()).isEqualTo(id);
    }

    @Test
    void obtenerConIdInexistenteLanzaFacturaNoEncontradaException() {
        UUID idDesconocido = UUID.randomUUID();
        when(facturaRepository.findById(idDesconocido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facturaService.obtener(idDesconocido))
                .isInstanceOf(FacturaNoEncontradaException.class);
    }

    @Test
    void obtenerConFacturaExistenteYComprobanteAusenteLanzaIllegalStateException() throws Exception {
        UUID id = UUID.randomUUID();
        Factura factura = facturaConId(id);

        when(facturaRepository.findById(id)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facturaService.obtener(id))
                .isInstanceOf(IllegalStateException.class)
                .message().contains("Integridad violada");
    }
}
