package cr.ac.fractall.notificaciones;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Punto único de envío de correo transaccional del flujo de registro/verificación (sección
 * 3.1). Encapsula el "encolar si falla" descrito ahí: {@code ComprobanteReintentosJob} (otro
 * mecanismo, fuera de alcance de Release 1) no se toca ni se reutiliza.
 */
@Service
public class EmailNotificacionService {

    private final ResendEmailClient resendEmailClient;
    private final ColaReintentoEmailRepository colaReintentoEmailRepository;

    public EmailNotificacionService(
            ResendEmailClient resendEmailClient,
            ColaReintentoEmailRepository colaReintentoEmailRepository) {
        this.resendEmailClient = resendEmailClient;
        this.colaReintentoEmailRepository = colaReintentoEmailRepository;
    }

    /**
     * Intenta el envío inmediato; si falla, inserta una fila en {@code cola_reintento_email}
     * en lugar de solo loguear y descartar el correo (sección 3.1: "si el envío falla por el
     * tope diario del proveedor, se encola y reintenta automáticamente").
     *
     * <p>{@code @Transactional} cubre únicamente el posible INSERT de la cola -- el envío en
     * sí no es una operación transaccional de base de datos. El llamador es responsable de
     * fijar {@link cr.ac.fractall.tenant.TenantContext} antes de invocar este método (ver
     * {@link cr.ac.fractall.tenant.TenantContextDescartable}), dado que este servicio no
     * corre necesariamente detrás de un JWT con tenant ya resuelto.
     */
    @Transactional
    public void enviarConReintento(String destinatario, String asunto, String cuerpoHtml) {
        boolean enviado = resendEmailClient.enviar(destinatario, asunto, cuerpoHtml);
        if (enviado) {
            return;
        }

        ColaReintentoEmail item = new ColaReintentoEmail();
        item.setDestinatario(destinatario);
        item.setAsunto(asunto);
        item.setCuerpoHtml(cuerpoHtml);
        item.setIntentos(0);
        item.setProximoIntento(LocalDateTime.now().plusMinutes(EmailReintentoScheduledJob.BACKOFF_BASE_MINUTOS));
        item.setEstado("PENDIENTE");
        item.setCreateDate(LocalDateTime.now());
        colaReintentoEmailRepository.save(item);
    }
}
