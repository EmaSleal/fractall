package cr.ac.fractall.notificaciones.servicio;

import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente REST de Resend (sección 2 y 3.1 de {@code arquitectura-facturacion-electronica-cr.md}):
 * proveedor de correo transaccional ya decidido -- subdominio dedicado + SPF/DKIM/DMARC son
 * responsabilidad de configuración de DNS, fuera del alcance de este cliente HTTP.
 *
 * <p>{@code application.notificaciones.resend.api-key} NO tiene valor por defecto: igual
 * que {@code VAULT_ROLE_ID}/{@code VAULT_SECRET_ID} en {@link cr.ac.fractall.secretos.VaultConfig},
 * una API key de un proveedor de correo real no tiene un "modo desarrollo" seguro -- si
 * falta, Spring debe fallar al resolver el placeholder durante el arranque del contexto
 * (falla ruidosa en startup), no silenciosamente en el primer envío.
 *
 * <p>{@link #enviar} nunca lanza ante un fallo de entrega normal (cuota diaria agotada,
 * error 4xx/5xx del proveedor, timeout de red): devuelve {@code false} y deja que el
 * llamador decida (ver {@link EmailNotificacionService}, que encola el reintento). Solo un
 * fallo de configuración (API key ausente) debe interrumpir el arranque -- una vez arrancada
 * la aplicación, un problema de entrega es exactamente lo que la cola de reintento existe
 * para absorber.
 */
@Component
public class ResendEmailClient {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);

    private final RestClient restClient;
    private final String remitente;

    public ResendEmailClient(
            @Value("${application.notificaciones.resend.api-key}") String apiKey,
            @Value("${application.notificaciones.resend.remitente}") String remitente,
            @Value("${application.notificaciones.resend.api-url:https://api.resend.com/emails}") String apiUrl) {
        this.remitente = remitente;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * Envía un correo vía {@code POST https://api.resend.com/emails}. Devuelve {@code true}
     * solo ante una respuesta 2xx del proveedor; cualquier otro caso (4xx/5xx, timeout,
     * error de red) devuelve {@code false} sin lanzar -- el reintento es responsabilidad del
     * llamador, no de este cliente.
     */
    public boolean enviar(String destinatario, String asunto, String cuerpoHtml) {
        SolicitudCorreo solicitud = new SolicitudCorreo(remitente, List.of(destinatario), asunto, cuerpoHtml);
        try {
            HttpStatusCode estado = restClient.post()
                    .body(solicitud)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return estado.is2xxSuccessful();
        } catch (RestClientException excepcion) {
            log.warn("Fallo al enviar correo vía Resend a {}: {}", destinatario, excepcion.getMessage());
            return false;
        }
    }

    /**
     * Envía un correo vía {@code POST https://api.resend.com/emails} con adjuntos en base64.
     * El contenido de cada {@link Adjunto} debe estar en texto claro -- este método lo
     * codifica en base64 antes de enviarlo según el formato esperado por la API de Resend.
     * Misma garantía que {@link #enviar(String, String, String)}: devuelve {@code false} ante
     * cualquier error de red/proveedor sin lanzar.
     *
     * <p>Agregado en Fase 9 para el flujo de entrega al cliente con PDF y XMLs adjuntos.
     */
    public boolean enviar(String destinatario, String asunto, String cuerpoHtml, List<Adjunto> adjuntos) {
        List<AdjuntoJson> adjuntosJson = adjuntos.stream()
                .map(a -> new AdjuntoJson(a.filename(), Base64.getEncoder().encodeToString(a.content())))
                .toList();
        SolicitudCorreoConAdjuntos solicitud = new SolicitudCorreoConAdjuntos(
                remitente, List.of(destinatario), asunto, cuerpoHtml, adjuntosJson);
        try {
            HttpStatusCode estado = restClient.post()
                    .body(solicitud)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return estado.is2xxSuccessful();
        } catch (RestClientException excepcion) {
            log.warn("Fallo al enviar correo con adjuntos vía Resend a {}: {}", destinatario, excepcion.getMessage());
            return false;
        }
    }

    /** Forma exacta del cuerpo JSON esperado por la API de Resend. */
    record SolicitudCorreo(String from, List<String> to, String subject, String html) {
    }

    /** Variante del cuerpo JSON para envíos con adjuntos (Fase 9). */
    record SolicitudCorreoConAdjuntos(String from, List<String> to, String subject, String html,
            List<AdjuntoJson> attachments) {
    }

    /** Representación JSON de un adjunto según la API de Resend: filename + content en base64. */
    record AdjuntoJson(String filename, String content) {
    }
}
