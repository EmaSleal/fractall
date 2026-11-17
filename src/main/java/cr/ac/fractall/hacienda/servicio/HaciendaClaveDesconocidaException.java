package cr.ac.fractall.hacienda.servicio;

/**
 * Hacienda respondió a la consulta por clave numérica que nunca recibió ese comprobante. La
 * clasificación vive en
 * {@code HaciendaComprobanteApiServiceImpl#esClaveDesconocidaPorHacienda} (ver su javadoc para la
 * evidencia de P-018).
 *
 * <p>No es una falla de configuración ni de comunicación (ADR-063): significa que el POST inicial
 * nunca llegó a Hacienda, y se resuelve reenviando el comprobante. Por eso NO extiende
 * {@link HaciendaConfiguracionException} (antes de T-008 sí lo hacía y el job de polling lo
 * escalaba como credencial rota, ADR-049) y extiende {@link RuntimeException} directamente.
 * {@code ComprobanteHaciendaEnvioService#consultarYReenviarSiClaveNoRecibida} la captura y reenvía:
 * lo usan el job de polling (en el mismo ciclo, dentro del presupuesto de intentos) y el reenvío
 * manual de un comprobante en ERROR (ADR-055).
 *
 * <p>Tampoco se reintenta con {@code @Retry} ni cuenta como fallo del circuit breaker
 * {@code haciendaAPI}: está en {@code ignore-exceptions} de ambos en {@code application.yaml}.
 */
public class HaciendaClaveDesconocidaException extends RuntimeException {

    public HaciendaClaveDesconocidaException(String message, Throwable cause) {
        super(message, cause);
    }
}
