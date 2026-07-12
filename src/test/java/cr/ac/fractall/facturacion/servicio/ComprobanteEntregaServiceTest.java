package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.pdf.FacturaPdfService;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.notificaciones.servicio.Adjunto;
import cr.ac.fractall.notificaciones.servicio.ResendEmailClient;

/**
 * Prueba unitaria de {@link ComprobanteEntregaService} (sin contexto de Spring, todos los
 * colaboradores mockeados). Cubre los escenarios de la especificación SC-01, SC-03, SC-04,
 * SC-05, SC-06.
 */
class ComprobanteEntregaServiceTest {

    private FacturaPdfService facturaPdfService;
    private ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader;
    private ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private FacturaRepository facturaRepository;
    private ClienteRepository clienteRepository;
    private ResendEmailClient resendEmailClient;
    private ComprobanteEntregaService servicio;

    private static final UUID COMPROBANTE_ID = UUID.randomUUID();
    private static final UUID FACTURA_ID = UUID.randomUUID();
    private static final UUID CLIENTE_ID = UUID.randomUUID();
    private static final UUID EMPRESA_ID = UUID.randomUUID();
    private static final String CONSECUTIVO = "00100001010000000001";
    private static final String CLAVE_NUMERICA = "50601012400310268958200100001010000000001199999999";
    private static final String XML_COMPROBANTE_REF = "empresas/" + EMPRESA_ID + "/comprobantes/" + CLAVE_NUMERICA + ".xml.enc";
    private static final String XML_RESPUESTA_REF = "empresas/" + EMPRESA_ID + "/comprobantes/" + CLAVE_NUMERICA + "-respuesta.xml.enc";
    private static final String PDF_REF = "empresas/" + EMPRESA_ID + "/comprobantes/" + CLAVE_NUMERICA + ".pdf.enc";
    private static final byte[] PDF_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF magic
    private static final byte[] XML_FACTURA_BYTES = "<FacturaElectronica/>".getBytes();
    private static final byte[] XML_RESPUESTA_BYTES = "<MensajeHacienda/>".getBytes();

    @BeforeEach
    void configurar() {
        facturaPdfService = mock(FacturaPdfService.class);
        comprobanteXmlCifradoUploader = mock(ComprobanteXmlCifradoUploader.class);
        comprobanteXmlCifradoDescargador = mock(ComprobanteXmlCifradoDescargador.class);
        comprobanteElectronicoRepository = mock(ComprobanteElectronicoRepository.class);
        facturaRepository = mock(FacturaRepository.class);
        clienteRepository = mock(ClienteRepository.class);
        resendEmailClient = mock(ResendEmailClient.class);

        servicio = new ComprobanteEntregaService(
                facturaPdfService,
                comprobanteXmlCifradoUploader,
                comprobanteXmlCifradoDescargador,
                comprobanteElectronicoRepository,
                facturaRepository,
                clienteRepository,
                resendEmailClient);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ComprobanteElectronico nuevoComprobante(String xmlRespuestaReferencia) {
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        ReflectionTestUtils.setField(comprobante, "id", COMPROBANTE_ID);
        ReflectionTestUtils.setField(comprobante, "empresaId", EMPRESA_ID);
        comprobante.setFacturaId(FACTURA_ID);
        comprobante.setConsecutivo(CONSECUTIVO);
        comprobante.setClaveNumerica(CLAVE_NUMERICA);
        comprobante.setEstado("ACEPTADO");
        comprobante.setXmlComprobanteReferencia(XML_COMPROBANTE_REF);
        comprobante.setXmlRespuestaReferencia(xmlRespuestaReferencia);
        return comprobante;
    }

    private Factura nuevaFactura() {
        Factura factura = new Factura();
        ReflectionTestUtils.setField(factura, "id", FACTURA_ID);
        ReflectionTestUtils.setField(factura, "empresaId", EMPRESA_ID);
        factura.setClienteId(CLIENTE_ID);
        return factura;
    }

    private Cliente nuevoCliente(String email) {
        Cliente cliente = new Cliente();
        ReflectionTestUtils.setField(cliente, "id", CLIENTE_ID);
        cliente.setNombre("Cliente de prueba");
        cliente.setEmail(email);
        return cliente;
    }

    // -------------------------------------------------------------------------
    // SC-01: Happy path — 3 attachments (xmlRespuestaReferencia presente)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void sc01HappyPathConRespuestaXmlEnviaCorreoConTresAdjuntos() {
        ComprobanteElectronico comprobante = nuevoComprobante(XML_RESPUESTA_REF);
        Factura factura = nuevaFactura();
        Cliente cliente = nuevoCliente("cliente@test.com");

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(clienteRepository.findById(CLIENTE_ID)).thenReturn(Optional.of(cliente));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString())).thenReturn(PDF_REF);
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(XML_COMPROBANTE_REF)).thenReturn(XML_FACTURA_BYTES);
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(XML_RESPUESTA_REF)).thenReturn(XML_RESPUESTA_BYTES);
        when(resendEmailClient.enviar(anyString(), anyString(), anyString(), anyList())).thenReturn(true);

        servicio.entregar(COMPROBANTE_ID);

        // Phase A: PDF should be uploaded and referencia persisted
        ArgumentCaptor<byte[]> pdfCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> rutaCaptor = ArgumentCaptor.forClass(String.class);
        verify(comprobanteXmlCifradoUploader).cifrarYSubir(pdfCaptor.capture(), rutaCaptor.capture());
        assertThat(pdfCaptor.getValue()).isEqualTo(PDF_BYTES);
        assertThat(rutaCaptor.getValue()).isEqualTo("empresas/" + EMPRESA_ID + "/comprobantes/" + CLAVE_NUMERICA + ".pdf.enc");
        assertThat(comprobante.getPdfReferencia()).isEqualTo(PDF_REF);
        verify(comprobanteElectronicoRepository).save(comprobante);

        // Phase B: email sent with 3 attachments
        ArgumentCaptor<List<Adjunto>> adjuntosCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(resendEmailClient).enviar(
                eq("cliente@test.com"),
                eq("Factura Electronica " + CONSECUTIVO),
                anyString(),
                adjuntosCaptor.capture());

        List<Adjunto> adjuntos = adjuntosCaptor.getValue();
        assertThat(adjuntos).hasSize(3);
        assertThat(adjuntos.get(0).filename()).isEqualTo("factura-" + CONSECUTIVO + ".pdf");
        assertThat(adjuntos.get(0).content()).isEqualTo(PDF_BYTES);
        assertThat(adjuntos.get(1).filename()).isEqualTo("factura-" + CONSECUTIVO + ".xml");
        assertThat(adjuntos.get(1).content()).isEqualTo(XML_FACTURA_BYTES);
        assertThat(adjuntos.get(2).filename()).isEqualTo("respuesta-hacienda-" + CONSECUTIVO + ".xml");
        assertThat(adjuntos.get(2).content()).isEqualTo(XML_RESPUESTA_BYTES);
    }

    // -------------------------------------------------------------------------
    // SC-06: null xmlRespuestaReferencia — only 2 attachments
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void sc06SinXmlRespuestaReferenciaEnviaCorreoConDosAdjuntos() {
        ComprobanteElectronico comprobante = nuevoComprobante(null); // no respuesta xml
        Factura factura = nuevaFactura();
        Cliente cliente = nuevoCliente("cliente@test.com");

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(clienteRepository.findById(CLIENTE_ID)).thenReturn(Optional.of(cliente));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString())).thenReturn(PDF_REF);
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(XML_COMPROBANTE_REF)).thenReturn(XML_FACTURA_BYTES);
        when(resendEmailClient.enviar(anyString(), anyString(), anyString(), anyList())).thenReturn(true);

        servicio.entregar(COMPROBANTE_ID);

        // descargador called exactly once (xmlComprobanteReferencia only)
        verify(comprobanteXmlCifradoDescargador).descargarYDescifrar(XML_COMPROBANTE_REF);
        verify(comprobanteXmlCifradoDescargador, never()).descargarYDescifrar(XML_RESPUESTA_REF);

        // email with exactly 2 attachments
        ArgumentCaptor<List<Adjunto>> adjuntosCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(resendEmailClient).enviar(anyString(), anyString(), anyString(), adjuntosCaptor.capture());
        assertThat(adjuntosCaptor.getValue()).hasSize(2);
        assertThat(adjuntosCaptor.getValue().get(0).filename()).isEqualTo("factura-" + CONSECUTIVO + ".pdf");
        assertThat(adjuntosCaptor.getValue().get(1).filename()).isEqualTo("factura-" + CONSECUTIVO + ".xml");
    }

    // -------------------------------------------------------------------------
    // SC-03: cliente.email null — Phase A completes, email skipped
    // -------------------------------------------------------------------------

    @Test
    void sc03EmailClienteNuloPdfPersistidoCorreoOmitido() {
        ComprobanteElectronico comprobante = nuevoComprobante(XML_RESPUESTA_REF);
        Factura factura = nuevaFactura();
        Cliente cliente = nuevoCliente(null); // null email

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(clienteRepository.findById(CLIENTE_ID)).thenReturn(Optional.of(cliente));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString())).thenReturn(PDF_REF);

        // Should not throw
        servicio.entregar(COMPROBANTE_ID);

        // Phase A completed: pdfReferencia persisted
        assertThat(comprobante.getPdfReferencia()).isEqualTo(PDF_REF);
        verify(comprobanteElectronicoRepository).save(comprobante);

        // Phase B skipped: no email sent, no XML downloads
        verifyNoInteractions(resendEmailClient);
        verifyNoInteractions(comprobanteXmlCifradoDescargador);
    }

    // -------------------------------------------------------------------------
    // SC-03b: cliente.email blank — same as null
    // -------------------------------------------------------------------------

    @Test
    void sc03bEmailClienteBlankoPdfPersistidoCorreoOmitido() {
        ComprobanteElectronico comprobante = nuevoComprobante(null);
        Factura factura = nuevaFactura();
        Cliente cliente = nuevoCliente("   "); // blank email

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(clienteRepository.findById(CLIENTE_ID)).thenReturn(Optional.of(cliente));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString())).thenReturn(PDF_REF);

        servicio.entregar(COMPROBANTE_ID);

        assertThat(comprobante.getPdfReferencia()).isEqualTo(PDF_REF);
        verify(comprobanteElectronicoRepository).save(comprobante);
        verifyNoInteractions(resendEmailClient);
    }

    // -------------------------------------------------------------------------
    // SC-04: Phase A failure (uploader throws) — exception propagates, no save
    // -------------------------------------------------------------------------

    @Test
    void sc04UploaderLanzaExcepcionPropagaYNoPersistePdfReferencia() {
        ComprobanteElectronico comprobante = nuevoComprobante(null);
        Factura factura = nuevaFactura();

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("OCI connection refused"));

        assertThatThrownBy(() -> servicio.entregar(COMPROBANTE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OCI connection refused");

        // pdf_referencia must NOT be persisted
        verify(comprobanteElectronicoRepository, never()).save(any());
        assertThat(comprobante.getPdfReferencia()).isNull();
        verifyNoInteractions(resendEmailClient);
    }

    // -------------------------------------------------------------------------
    // SC-05: Phase B failure (ResendEmailClient throws) — exception swallowed
    // -------------------------------------------------------------------------

    @Test
    void sc05ResendLanzaExcepcionEsSwallowedYPdfReferenciaPersistida() {
        ComprobanteElectronico comprobante = nuevoComprobante(null);
        Factura factura = nuevaFactura();
        Cliente cliente = nuevoCliente("cliente@test.com");

        when(comprobanteElectronicoRepository.findById(COMPROBANTE_ID)).thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(FACTURA_ID)).thenReturn(Optional.of(factura));
        when(clienteRepository.findById(CLIENTE_ID)).thenReturn(Optional.of(cliente));
        when(facturaPdfService.generarPdf(COMPROBANTE_ID)).thenReturn(PDF_BYTES);
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), anyString())).thenReturn(PDF_REF);
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(anyString())).thenReturn(XML_FACTURA_BYTES);
        when(resendEmailClient.enviar(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("Resend API down"));

        // Should NOT throw (Phase B exception swallowed)
        servicio.entregar(COMPROBANTE_ID);

        // Phase A persisted
        assertThat(comprobante.getPdfReferencia()).isEqualTo(PDF_REF);
        verify(comprobanteElectronicoRepository).save(comprobante);
    }
}
