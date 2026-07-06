package cr.ac.fractall.seguridad.servicio;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Límite de tasa por IP, exclusivo de {@code POST /auth/reenviar-verificacion} (sección
 * 3.1: "un reenvío cada 5 minutos"). Deliberadamente un {@link ConcurrentHashMap} acotado a
 * este único endpoint, no un framework de rate-limiting de propósito general -- eso sería
 * sobre-ingeniería para un solo punto de entrada de bajo volumen.
 *
 * <p>El límite por email (reutilizando {@code usuario_token.create_date}) vive en
 * {@link VerificacionEmailService}, no aquí -- esta clase solo cubre el eje "IP de origen".
 */
@Component
public class ReenvioVerificacionRateLimiter {

    private static final Duration VENTANA = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, Instant> ultimoIntentoPorIp = new ConcurrentHashMap<>();

    /** {@code true} si esta IP no reenvió dentro de la ventana vigente (y queda registrada ahora). */
    public boolean permitido(String ip) {
        Instant ahora = Instant.now();
        Instant limite = ahora.minus(VENTANA);
        boolean[] permitido = {true};

        ultimoIntentoPorIp.compute(ip, (clave, anterior) -> {
            if (anterior != null && anterior.isAfter(limite)) {
                permitido[0] = false;
                return anterior;
            }
            return ahora;
        });

        return permitido[0];
    }
}
