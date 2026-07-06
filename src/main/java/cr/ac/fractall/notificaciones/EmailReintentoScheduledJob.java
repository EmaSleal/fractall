package cr.ac.fractall.notificaciones;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Reintento automático del correo de verificación de email ante fallo de envío (sección 3.1:
 * "si el envío falla por el tope diario del proveedor, se encola y reintenta
 * automáticamente"). Acotado a {@code cola_reintento_email} -- no es
 * {@code ComprobanteReintentosJob} (fuera de alcance de Release 1).
 *
 * <p><b>Intervalo (15 minutos):</b> el tope gratuito de Resend es diario (100/día); un
 * intervalo de 15-30 minutos da varias oportunidades de reintento dentro de una misma
 * ventana de 24h sin sondear con una frecuencia que no aporta nada (el tope no se libera
 * más rápido por sondear más seguido). Se elige el extremo más frecuente del rango (15 min)
 * porque el token de verificación asociado expira a las 24h -- cuanto antes se reintente,
 * más margen queda para que el usuario todavía pueda verificar antes de que el token expire.
 *
 * <p><b>Backoff exponencial (base 15 min, tope 4h):</b> {@code 15 * 2^(intentos-1)} minutos,
 * capado en {@value #BACKOFF_CAP_MINUTOS} minutos. Tras {@value #MAX_INTENTOS} intentos
 * fallidos (~4 horas de ventana total) se marca {@code AGOTADO} en lugar de seguir
 * reintentando: no tiene sentido seguir reintentando mucho más allá de eso porque el enlace
 * de verificación ya expira a las 24h de todos modos (ver {@code RegistroService}) -- un
 * reintento indefinido no cambiaría el resultado para el usuario.
 */
@Component
public class EmailReintentoScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(EmailReintentoScheduledJob.class);

    static final long BACKOFF_BASE_MINUTOS = 15;
    static final long BACKOFF_CAP_MINUTOS = 240;
    static final int MAX_INTENTOS = 5;

    private final ColaReintentoEmailRepository colaReintentoEmailRepository;
    private final ResendEmailClient resendEmailClient;

    public EmailReintentoScheduledJob(
            ColaReintentoEmailRepository colaReintentoEmailRepository,
            ResendEmailClient resendEmailClient) {
        this.colaReintentoEmailRepository = colaReintentoEmailRepository;
        this.resendEmailClient = resendEmailClient;
    }

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void procesarPendientes() {
        TenantContextDescartable.ejecutar((Runnable) this::procesarPendientesTransaccional);
    }

    @Transactional
    void procesarPendientesTransaccional() {
        List<ColaReintentoEmail> pendientes = colaReintentoEmailRepository
                .findByEstadoAndProximoIntentoLessThanEqual("PENDIENTE", LocalDateTime.now());

        for (ColaReintentoEmail item : pendientes) {
            boolean enviado = resendEmailClient.enviar(item.getDestinatario(), item.getAsunto(), item.getCuerpoHtml());
            if (enviado) {
                item.setEstado("ENVIADO");
                colaReintentoEmailRepository.save(item);
                continue;
            }

            int intentos = item.getIntentos() + 1;
            item.setIntentos(intentos);
            if (intentos >= MAX_INTENTOS) {
                item.setEstado("AGOTADO");
                log.warn("Correo a {} agotó sus {} reintentos vía Resend; se descarta.",
                        item.getDestinatario(), MAX_INTENTOS);
            } else {
                long backoffMinutos = Math.min(BACKOFF_BASE_MINUTOS * (1L << (intentos - 1)), BACKOFF_CAP_MINUTOS);
                item.setProximoIntento(LocalDateTime.now().plusMinutes(backoffMinutos));
            }
            colaReintentoEmailRepository.save(item);
        }
    }
}
