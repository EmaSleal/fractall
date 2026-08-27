package cr.ac.fractall.hacienda.servicio;

/**
 * Falla de consulta/envío a Hacienda cuya causa es de <b>configuración</b>: credencial ausente
 * ({@code credencial_hacienda} sin fila para la combinación empresa+ambiente), contraseña ausente
 * en Vault, certificado inválido o ilegible, o rechazo persistente (401) de Hacienda incluso
 * después del reintento inline de renovación de token.
 *
 * <p>Se diferencia de {@link HaciendaComunicacionException} porque un reintento automático NO
 * puede resolverla: requiere intervención humana para corregir la credencial o el certificado
 * antes de que la próxima consulta tenga alguna chance de éxito. El job de sondeo
 * ({@code ComprobanteHaciendaPollingScheduledJob}) usa esta distinción para escalar en un solo
 * intento en vez de agotar el presupuesto de reintentos de una falla de comunicación transitoria.
 */
public class HaciendaConfiguracionException extends RuntimeException {

    public HaciendaConfiguracionException(String message) {
        super(message);
    }

    public HaciendaConfiguracionException(String message, Throwable cause) {
        super(message, cause);
    }
}
