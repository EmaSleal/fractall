package cr.ac.fractall.hacienda.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job diario (7pm por defecto) que garantiza que {@code tipo_cambio_dolar} tenga un valor para
 * hoy, como respaldo de las consultas que el propio {@code HaciendaApiService#consultarTipoCambioDolar}
 * ya dispara bajo demanda (p. ej. al facturar en USD).
 *
 * <p>Sin lógica condicional propia: {@link HaciendaApiService#consultarTipoCambioDolar} ya es
 * cache-aware (ver su javadoc y el de {@code HaciendaConsultaServiceImpl}) -- si alguien ya lo
 * consultó hoy, este job no vuelve a golpear Hacienda; si nadie lo consultó todavía, lo trae y lo
 * guarda. El job delega esa decisión por completo al método, así que no necesita chequear primero
 * si el valor de hoy ya existe.
 *
 * <p>Un fallo de la llamada ({@link TipoCambioNoDisponibleException} u otra
 * {@link RuntimeException}) se loguea y se traga -- mismo principio que
 * {@code CabysReconciliacionJob}: un fallo de este job de respaldo no debe tumbar el scheduler ni
 * escalar, porque el valor de hoy simplemente se reintentará bajo demanda en la próxima consulta.
 */
@Component
public class TipoCambioScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(TipoCambioScheduledJob.class);

    private final HaciendaApiService haciendaApiService;

    public TipoCambioScheduledJob(HaciendaApiService haciendaApiService) {
        this.haciendaApiService = haciendaApiService;
    }

    @Scheduled(cron = "${application.hacienda.tipo-cambio.fallback-cron:0 0 19 * * *}")
    public void ejecutar() {
        try {
            haciendaApiService.consultarTipoCambioDolar();
        } catch (RuntimeException excepcion) {
            log.error("Error en el job de respaldo del tipo de cambio del dólar: {}", excepcion.getMessage(), excepcion);
        }
    }
}
