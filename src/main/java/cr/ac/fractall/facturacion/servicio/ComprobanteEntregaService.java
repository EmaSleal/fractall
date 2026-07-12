package cr.ac.fractall.facturacion.servicio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.pdf.FacturaPdfService;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.notificaciones.servicio.Adjunto;
import cr.ac.fractall.notificaciones.servicio.ResendEmailClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquesta la entrega del comprobante electrónico al cliente una vez que Hacienda lo aceptó:
 * genera el PDF, lo cifra y sube a OCI (Fase A, fallo duro), luego descarga los XMLs, arma los
 * adjuntos y envía el correo (Fase B, fallo suave atrapado en catch).
 *
 * <p>Deliberadamente sin {@code @Transactional}: contiene llamadas de red a OCI y a Resend fuera
 * de cualquier transacción larga; la única escritura de base de datos ({@code save()} en Fase A)
 * se apoya en que {@code SimpleJpaRepository#save} ya es transaccional por sí mismo. Mismo patrón
 * ya documentado en {@link ComprobanteXmlPersistenceService}.
 *
 * <p>Fase A (fallo duro — la excepción se propaga):
 * <ol>
 *   <li>Carga el {@code ComprobanteElectronico} y la {@code Factura}.
 *   <li>Genera el PDF vía {@link FacturaPdfService}.
 *   <li>Cifra y sube el PDF a OCI.
 *   <li>Persiste {@code pdf_referencia} en el comprobante.
 * </ol>
 *
 * <p>Fase B (fallo suave — atrapado en {@code catch(Exception)}, jamás se propaga):
 * <ol>
 *   <li>Carga el {@code Cliente}; si su email es nulo/vacío registra advertencia y sale.
 *   <li>Descarga y descifra el XML del comprobante y, si existe, el XML de respuesta de Hacienda.
 *   <li>Arma la lista de adjuntos (PDF + XML factura + XML respuesta si aplica).
 *   <li>Envía el correo vía {@link ResendEmailClient}.
 * </ol>
 */
@Component
@Slf4j
class ComprobanteEntregaService {

    private final FacturaPdfService facturaPdfService;
    private final ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader;
    private final ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final FacturaRepository facturaRepository;
    private final ClienteRepository clienteRepository;
    private final ResendEmailClient resendEmailClient;

    ComprobanteEntregaService(
            FacturaPdfService facturaPdfService,
            ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader,
            ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            FacturaRepository facturaRepository,
            ClienteRepository clienteRepository,
            ResendEmailClient resendEmailClient) {
        this.facturaPdfService = facturaPdfService;
        this.comprobanteXmlCifradoUploader = comprobanteXmlCifradoUploader;
        this.comprobanteXmlCifradoDescargador = comprobanteXmlCifradoDescargador;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.facturaRepository = facturaRepository;
        this.clienteRepository = clienteRepository;
        this.resendEmailClient = resendEmailClient;
    }

    /**
     * Entrega el comprobante electrónico al cliente. Ver el javadoc de la clase para las dos fases
     * y su semántica de fallo.
     *
     * @param comprobanteId id del {@link ComprobanteElectronico} que acaba de ser aceptado por Hacienda
     */
    void entregar(UUID comprobanteId) {

        // =====================================================================
        // FASE A — fallo duro: la excepción se propaga al llamador si cualquier
        // paso falla. pdf_referencia solo queda en disco cuando TODO esto va bien.
        // =====================================================================

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

        Factura factura = facturaRepository.findById(comprobante.getFacturaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Factura no encontrada para comprobante " + comprobanteId
                                + ": " + comprobante.getFacturaId()));

        byte[] pdf = facturaPdfService.generarPdf(comprobanteId);

        String rutaPdf = construirRutaPdf(factura.getEmpresaId(), comprobante.getClaveNumerica());
        String pdfReferencia = comprobanteXmlCifradoUploader.cifrarYSubir(pdf, rutaPdf);

        // self-transactional save (SimpleJpaRepository#save es @Transactional)
        comprobante.setPdfReferencia(pdfReferencia);
        comprobanteElectronicoRepository.save(comprobante);

        log.info("PDF del comprobante {} persistido en OCI: {}", comprobanteId, pdfReferencia);

        // =====================================================================
        // FASE B — fallo suave: cualquier error queda atrapado, nunca llega al
        // llamador. pdf_referencia ya está duradero antes de llegar aquí.
        // =====================================================================

        try {
            Cliente cliente = clienteRepository.findById(factura.getClienteId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cliente no encontrado para factura " + factura.getClienteId()));

            if (cliente.getEmail() == null || cliente.getEmail().isBlank()) {
                log.warn("cliente.email es nulo o vacío, omitiendo entrega por correo para comprobante {}",
                        comprobanteId);
                return;
            }

            byte[] facturaXml = comprobanteXmlCifradoDescargador
                    .descargarYDescifrar(comprobante.getXmlComprobanteReferencia());

            List<Adjunto> adjuntos = new ArrayList<>();
            adjuntos.add(new Adjunto("factura-" + comprobante.getConsecutivo() + ".pdf", pdf));
            adjuntos.add(new Adjunto("factura-" + comprobante.getConsecutivo() + ".xml", facturaXml));

            if (comprobante.getXmlRespuestaReferencia() != null) {
                byte[] respuestaXml = comprobanteXmlCifradoDescargador
                        .descargarYDescifrar(comprobante.getXmlRespuestaReferencia());
                adjuntos.add(new Adjunto("respuesta-hacienda-" + comprobante.getConsecutivo() + ".xml", respuestaXml));
            }

            String asunto = "Factura Electronica " + comprobante.getConsecutivo();
            String cuerpoHtml = construirCuerpoHtml(cliente.getNombre(), comprobante);

            boolean enviado = resendEmailClient.enviar(cliente.getEmail(), asunto, cuerpoHtml, adjuntos);
            log.debug("Correo de entrega para comprobante {} enviado={}", comprobanteId, enviado);

        } catch (Exception e) {
            log.error("Fallo en entrega de comprobante {}: {}", comprobanteId, e.getMessage(), e);
        }
    }

    /**
     * {@code empresas/{empresaId}/comprobantes/{claveNumerica}.pdf.enc} -- mismo principio de ruta
     * legible y determinística que el XML firmado; extensión {@code .pdf.enc} garantiza que nunca
     * choca con el objeto XML del mismo comprobante.
     */
    private static String construirRutaPdf(UUID empresaId, String claveNumerica) {
        return "empresas/" + empresaId + "/comprobantes/" + claveNumerica + ".pdf.enc";
    }

    private static String construirCuerpoHtml(String nombreCliente, ComprobanteElectronico comprobante) {
        return "<p>Estimado(a) " + nombreCliente + ",</p>"
                + "<p>Adjunto encontrará su factura electrónica:</p>"
                + "<ul>"
                + "<li>Consecutivo: " + comprobante.getConsecutivo() + "</li>"
                + "<li>Clave numérica: " + comprobante.getClaveNumerica() + "</li>"
                + "</ul>"
                + "<p>PDF de la factura, XML de la factura electrónica"
                + (comprobante.getXmlRespuestaReferencia() != null
                        ? " y XML de respuesta de Hacienda" : "")
                + " incluidos como adjuntos.</p>";
    }
}
