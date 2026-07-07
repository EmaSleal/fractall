package cr.ac.fractall.hacienda.dto;

/**
 * Mensaje pendiente de respuesta reportado por el endpoint de mensajes-receptor de Hacienda.
 *
 * <p>Portado (Categoría A) de {@code HaciendaApiService.MensajeHaciendaResponse} -- ver el
 * javadoc de {@link TokenHaciendaDTO} sobre por qué se extrae a un archivo propio en vez de
 * quedar anidado en la interfaz.
 */
public record MensajeHaciendaDTO(
        String claveNumerica,
        String mensaje,
        String detalle,
        String estado) {
}
