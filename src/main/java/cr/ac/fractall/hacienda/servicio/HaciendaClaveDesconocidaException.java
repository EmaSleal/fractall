package cr.ac.fractall.hacienda.servicio;

/**
 * Hacienda respondió a la consulta por clave numérica que nunca recibió ese comprobante. La
 * clasificación vive en
 * {@code HaciendaComprobanteApiServiceImpl#esClaveDesconocidaPorHacienda} (ver su javadoc para la
 * evidencia de P-018).
 *
 * <p>Extiende {@link HaciendaConfiguracionException} a propósito: antes de T-004 ese mismo 400 se
 * lanzaba como {@code HaciendaConfiguracionException}, y el job de polling (ADR-049) lo sigue
 * tratando igual. Solo el reenvío manual de un comprobante en ERROR (ADR-055) captura este subtipo
 * para decidir que sí debe hacer el POST.
 */
public class HaciendaClaveDesconocidaException extends HaciendaConfiguracionException {

    public HaciendaClaveDesconocidaException(String message, Throwable cause) {
        super(message, cause);
    }
}
