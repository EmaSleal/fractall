package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
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

/**
 * Prueba unitaria (sin contexto de Spring) de {@link FacturaService#reenviar} -- cubre todos los
 * caminos de validación del estado del comprobante y la lógica de reenvío. Req: FR-1, NFR-1,
 * NFR-3, NFR-4.
 */
class FacturaServiceReenviarTest {

    private ClienteRepository clienteRepository;
    private ProductoRepository productoRepository;
    private ClienteExoneracionRepository clienteExoneracionRepository;
    private EmpresaRepository empresaRepository;
    private ConsecutivoService consecutivoService;
    private FacturaRepository facturaRepository;
    private LineaFacturaRepository lineaFacturaRepository;
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private LineaDescuentoRepository lineaDescuentoRepository;
    private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private FacturaMedioPagoRepository facturaMedioPagoRepository;
    private ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;
    private ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;
    private HaciendaApiService haciendaApiService;

    private FacturaService facturaService;

    @BeforeEach
    void configurar() {
        clienteRepository = mock(ClienteRepository.class);
        productoRepository = mock(ProductoRepository.class);
        clienteExoneracionRepository = mock(ClienteExoneracionRepository.class);
        empresaRepository = mock(EmpresaRepository.class);
        consecutivoService = mock(ConsecutivoService.class);
        facturaRepository = mock(FacturaRepository.class);
        lineaFacturaRepository = mock(LineaFacturaRepository.class);
        comprobanteElectronicoRepository = mock(ComprobanteElectronicoRepository.class);
        lineaCodigoComercialRepository = mock(LineaCodigoComercialRepository.class);
        lineaDescuentoRepository = mock(LineaDescuentoRepository.class);
        impuestoLineaExoneracionRepository = mock(ImpuestoLineaExoneracionRepository.class);
        facturaOtrosCargosRepository = mock(FacturaOtrosCargosRepository.class);
        facturaInformacionReferenciaRepository = mock(FacturaInformacionReferenciaRepository.class);
        facturaMedioPagoRepository = mock(FacturaMedioPagoRepository.class);
        comprobanteXmlCifradoDescargador = mock(ComprobanteXmlCifradoDescargador.class);
        comprobanteHaciendaEnvioService = mock(ComprobanteHaciendaEnvioService.class);
        haciendaApiService = mock(HaciendaApiService.class);

        facturaService = new FacturaService(
                clienteRepository,
                productoRepository,
                clienteExoneracionRepository,
                empresaRepository,
                consecutivoService,
                facturaRepository,
                lineaFacturaRepository,
                comprobanteElectronicoRepository,
                lineaCodigoComercialRepository,
                lineaDescuentoRepository,
                impuestoLineaExoneracionRepository,
                facturaOtrosCargosRepository,
                facturaInformacionReferenciaRepository,
                facturaMedioPagoRepository,
                comprobanteXmlCifradoDescargador,
                comprobanteHaciendaEnvioService,
                haciendaApiService);
    }

    private static ComprobanteElectronico nuevoComprobante(UUID facturaId, String estado, String xmlRef) {
        ComprobanteElectronico c = new ComprobanteElectronico();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setFacturaId(facturaId);
        c.setEstado(estado);
        c.setXmlComprobanteReferencia(xmlRef);
        c.setIntentosEnvio(3);
        c.setIntentosConsulta(2);
        c.setAmbienteHacienda("SANDBOX");
        c.setTipoComprobante("01");
        c.setConsecutivo("00000000000000000001");
        c.setClaveNumerica("5" + "0".repeat(49));
        c.setFechaEmision(LocalDateTime.now());
        return c;
    }

    private Factura nuevaFactura(UUID facturaId) {
        Factura f = new Factura();
        ReflectionTestUtils.setField(f, "id", facturaId);
        f.setClienteId(UUID.randomUUID());
        f.setCondicionVenta("01");
        f.setMedioPago("01");
        f.setMoneda("CRC");
        f.setTipoCambio(BigDecimal.ONE);
        f.setSubtotal(new BigDecimal("1000.00000"));
        f.setTotalImpuesto(new BigDecimal("130.00000"));
        f.setTotal(new BigDecimal("1130.00000"));
        f.setTotalIvaDevuelto(BigDecimal.ZERO);
        f.setCreateDate(LocalDateTime.now());
        f.setUpdateDate(LocalDateTime.now());
        return f;
    }

    private void stubObtener(UUID facturaId, ComprobanteElectronico comprobante) {
        Factura factura = nuevaFactura(facturaId);
        when(facturaRepository.findById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId)).thenReturn(List.of());
        when(facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
        when(facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
        when(facturaMedioPagoRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
    }

    // ========== happy path — 3 reenvíable states ==========

    @Test
    void reenviar_conFirmado_descargaYEnviaYRetornaResponse() {
        UUID facturaId = UUID.randomUUID();
        String xmlRef = "empresas/x/comprobantes/test.xml.enc";
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "FIRMADO", xmlRef);
        byte[] xmlBytes = "<xml-firmado/>".getBytes(StandardCharsets.UTF_8);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(xmlRef)).thenReturn(xmlBytes);
        stubObtener(facturaId, comprobante);

        facturaService.reenviar(facturaId);

        verify(comprobanteXmlCifradoDescargador).descargarYDescifrar(xmlRef);
        verify(comprobanteHaciendaEnvioService).enviarComprobante(eq("<xml-firmado/>"), eq(comprobante));
    }

    @Test
    void reenviar_conRechazado_descargaYEnviaYRetornaResponse() {
        UUID facturaId = UUID.randomUUID();
        String xmlRef = "empresas/x/comprobantes/rechazado.xml.enc";
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "RECHAZADO", xmlRef);
        byte[] xmlBytes = "<xml-rechazado/>".getBytes(StandardCharsets.UTF_8);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(xmlRef)).thenReturn(xmlBytes);
        stubObtener(facturaId, comprobante);

        facturaService.reenviar(facturaId);

        verify(comprobanteHaciendaEnvioService).enviarComprobante(eq("<xml-rechazado/>"), eq(comprobante));
    }

    @Test
    void reenviar_conError_descargaYEnviaYRetornaResponse() {
        UUID facturaId = UUID.randomUUID();
        String xmlRef = "empresas/x/comprobantes/error.xml.enc";
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "ERROR", xmlRef);
        byte[] xmlBytes = "<xml-error/>".getBytes(StandardCharsets.UTF_8);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(xmlRef)).thenReturn(xmlBytes);
        stubObtener(facturaId, comprobante);

        facturaService.reenviar(facturaId);

        verify(comprobanteHaciendaEnvioService).enviarComprobante(eq("<xml-error/>"), eq(comprobante));
    }

    // ========== 409 non-reenvíable states ==========

    @Test
    void reenviar_conGenerado_lanzaComprobanteNoReenviable() {
        UUID facturaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "GENERADO", "ref");

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(comprobanteHaciendaEnvioService, never()).enviarComprobante(any(), any());
    }

    @Test
    void reenviar_conEnviado_lanzaComprobanteNoReenviable() {
        UUID facturaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "ENVIADO", "ref");

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(comprobanteHaciendaEnvioService, never()).enviarComprobante(any(), any());
    }

    @Test
    void reenviar_conAceptado_lanzaComprobanteNoReenviable() {
        UUID facturaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "ACEPTADO", "ref");

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(comprobanteHaciendaEnvioService, never()).enviarComprobante(any(), any());
    }

    @Test
    void reenviar_conXmlRefNull_lanzaComprobanteNoReenviable() {
        UUID facturaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "FIRMADO", null);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(comprobanteHaciendaEnvioService, never()).enviarComprobante(any(), any());
    }

    // ========== 404 not found ==========

    @Test
    void reenviar_facturaNoEncontrada_lanzaFacturaNoEncontrada() {
        UUID facturaId = UUID.randomUUID();

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(FacturaNoEncontradaException.class);
    }

    // ========== intentosEnvio reset ==========

    @Test
    void reenviar_reseteaIntentosEnvioACero() {
        UUID facturaId = UUID.randomUUID();
        String xmlRef = "empresas/x/comprobantes/reset.xml.enc";
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "RECHAZADO", xmlRef);
        comprobante.setIntentosEnvio(5);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(xmlRef))
                .thenReturn("<xml/>".getBytes(StandardCharsets.UTF_8));
        stubObtener(facturaId, comprobante);

        facturaService.reenviar(facturaId);

        verify(comprobanteElectronicoRepository).save(comprobante);
        // intentosEnvio must be 0 when saved (enviarComprobante will increment it to 1 inside)
        assert comprobante.getIntentosEnvio() == 0 : "intentosEnvio debe ser 0 antes del envio";
    }

    // ========== obtener called after send ==========

    @Test
    void reenviar_llamaObtenerDespuesDeEnviar() {
        UUID facturaId = UUID.randomUUID();
        String xmlRef = "empresas/x/comprobantes/post.xml.enc";
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId, "FIRMADO", xmlRef);

        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(xmlRef))
                .thenReturn("<xml/>".getBytes(StandardCharsets.UTF_8));
        stubObtener(facturaId, comprobante);

        facturaService.reenviar(facturaId);

        // obtener delegates to facturaRepository.findById — verify it was called (post-send path)
        verify(facturaRepository).findById(facturaId);
    }
}
