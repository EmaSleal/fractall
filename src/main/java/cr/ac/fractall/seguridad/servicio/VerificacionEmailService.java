package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioToken;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;

/**
 * Verificación de correo y reenvío del token (sección 3.1, puntos 3-4). El llamador (ver
 * {@code AuthController}) debe fijar {@link cr.ac.fractall.tenant.TenantContext} antes de
 * invocar cualquiera de estos métodos, vía {@link cr.ac.fractall.tenant.TenantContextDescartable}
 * -- mismo motivo que en {@link RegistroService}.
 */
@Service
public class VerificacionEmailService {

    private static final int TOKEN_BYTES = 32;
    private static final long EXPIRACION_HORAS = 24;
    private static final String TIPO_VERIFICACION_EMAIL = "VERIFICACION_EMAIL";

    /** Mismo umbral de 5 minutos que {@link ReenvioVerificacionRateLimiter}, aplicado por email. */
    private static final long VENTANA_REENVIO_MINUTOS = 5;

    private final UsuarioRepository usuarioRepository;
    private final UsuarioTokenRepository usuarioTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public VerificacionEmailService(
            UsuarioRepository usuarioRepository,
            UsuarioTokenRepository usuarioTokenRepository) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioTokenRepository = usuarioTokenRepository;
    }

    /**
     * Activa la cuenta si el token crudo recibido corresponde a un {@code usuario_token}
     * vigente, sin usar y no expirado. Devuelve {@code false} para CUALQUIER motivo de
     * rechazo (no encontrado, expirado, ya usado) -- el llamador no debe distinguir el
     * motivo en la respuesta HTTP (sección 3.1: "sin revelar el motivo específico").
     */
    @Transactional
    public boolean verificar(String tokenCrudo) {
        String hash = TokenHasher.sha256Hex(tokenCrudo);
        Optional<UsuarioToken> tokenOpt = usuarioTokenRepository
                .findByTokenHashAndUsadoFalseAndExpiraEnAfter(hash, LocalDateTime.now());
        if (tokenOpt.isEmpty()) {
            return false;
        }

        UsuarioToken token = tokenOpt.get();
        Usuario usuario = usuarioRepository.findById(token.getUsuarioId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_token " + token.getId() + " referencia un usuario_id inexistente: " + token.getUsuarioId()));

        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setUpdateDate(LocalDateTime.now());
        usuarioRepository.save(usuario);

        token.setUsado(true);
        usuarioTokenRepository.save(token);
        return true;
    }

    /**
     * Genera y persiste un nuevo token de verificación SOLO si el email es elegible: existe,
     * no está ya verificado, y no reenvió dentro de los últimos {@value #VENTANA_REENVIO_MINUTOS}
     * minutos (el {@code create_date} del último {@code usuario_token} de tipo
     * {@code VERIFICACION_EMAIL} de ese usuario es la señal de throttle -- no se crea una
     * tabla nueva solo para esto, ver sección 3.1).
     *
     * <p>Devuelve {@link Optional#empty()} para CUALQUIER motivo de no-elegibilidad -- el
     * llamador responde con el mismo mensaje genérico en todos los casos (anti-enumeración).
     */
    @Transactional
    public Optional<ReenvioResultado> generarTokenReenvioSiElegible(String email) {
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return Optional.empty();
        }

        Usuario usuario = usuarioOpt.get();
        if (usuario.isEmailVerificado()) {
            return Optional.empty();
        }

        Optional<UsuarioToken> ultimoToken = usuarioTokenRepository
                .findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(usuario.getId(), TIPO_VERIFICACION_EMAIL);
        LocalDateTime ahora = LocalDateTime.now();
        if (ultimoToken.isPresent()
                && ultimoToken.get().getCreateDate().isAfter(ahora.minusMinutes(VENTANA_REENVIO_MINUTOS))) {
            return Optional.empty();
        }

        String tokenCrudo = generarTokenCrudo();
        UsuarioToken nuevoToken = new UsuarioToken();
        nuevoToken.setUsuarioId(usuario.getId());
        nuevoToken.setTipo(TIPO_VERIFICACION_EMAIL);
        nuevoToken.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        nuevoToken.setExpiraEn(ahora.plusHours(EXPIRACION_HORAS));
        nuevoToken.setUsado(false);
        nuevoToken.setCreateDate(ahora);
        usuarioTokenRepository.save(nuevoToken);

        return Optional.of(new ReenvioResultado(usuario.getEmail(), tokenCrudo));
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ReenvioResultado(String email, String tokenCrudo) {
    }
}
