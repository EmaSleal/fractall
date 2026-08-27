package cr.ac.fractall.hacienda.servicio;

/**
 * Falla de consulta/envío a Hacienda cuya causa es de <b>comunicación</b>: timeout, conexión
 * rechazada, o cualquier error 5xx devuelto por Hacienda.
 *
 * <p>Se diferencia de {@link HaciendaConfiguracionException} porque es transitoria por naturaleza:
 * un reintento posterior (con el mismo credencial) tiene una chance razonable de éxito, así que el
 * job de sondeo ({@code ComprobanteHaciendaPollingScheduledJob}) mantiene para esta causa el
 * presupuesto existente de 10 intentos con backoff exponencial, en vez de escalar de inmediato.
 */
public class HaciendaComunicacionException extends RuntimeException {

    public HaciendaComunicacionException(String message) {
        super(message);
    }

    public HaciendaComunicacionException(String message, Throwable cause) {
        super(message, cause);
    }
}
