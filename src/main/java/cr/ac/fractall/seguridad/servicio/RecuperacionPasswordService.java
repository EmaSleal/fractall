package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioToken;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;

/**
 * Flujo de recuperación de contraseña (sección de especificación): generación del token y
 * restablecimiento atómico. Ambos métodos se invocan bajo
 * {@code TenantContextDescartable.ejecutar()} desde el controlador (endpoints no
 * autenticados).
 *
 * <p>Anti-enumeración: {@link #generarTokenSiElegible(String)} devuelve {@link Optional#empty()}
 * para CUALQUIER motivo de no-elegibilidad -- el controlador responde con el mismo mensaje
 * genérico en todos los casos.
 */
@Service
public class RecuperacionPasswordService {

    private static final int TOKEN_BYTES = 32;
    private static final long EXPIRACION_HORAS = 1;
    private static final String TIPO_RECUPERACION = "RECUPERACION_PASSWORD";
    private static final long VENTANA_THROTTLE_MINUTOS = 5;

    private final UsuarioRepository usuarioRepository;
    private final UsuarioTokenRepository usuarioTokenRepository;
    private final SesionRefreshTokenRepository sesionRefreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public RecuperacionPasswordService(
            UsuarioRepository usuarioRepository,
            UsuarioTokenRepository usuarioTokenRepository,
            SesionRefreshTokenRepository sesionRefreshTokenRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioTokenRepository = usuarioTokenRepository;
        this.sesionRefreshTokenRepository = sesionRefreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Genera y persiste un token de recuperación SOLO si el email es elegible: existe,
     * está verificado, y no tiene un token de recuperación creado en los últimos
     * {@value #VENTANA_THROTTLE_MINUTOS} minutos.
     *
     * <p>Devuelve {@link Optional#empty()} para CUALQUIER motivo de no-elegibilidad.
     */
    @Transactional
    public Optional<RecuperacionResultado> generarTokenSiElegible(String email) {
        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(email);
        if (usuarioOpt.isEmpty()) {
            return Optional.empty();
        }

        Usuario usuario = usuarioOpt.get();
        if (!usuario.isEmailVerificado()) {
            return Optional.empty();
        }

        Optional<UsuarioToken> ultimoToken = usuarioTokenRepository
                .findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(usuario.getId(), TIPO_RECUPERACION);
        LocalDateTime ahora = LocalDateTime.now();
        if (ultimoToken.isPresent()
                && ultimoToken.get().getCreateDate().isAfter(ahora.minusMinutes(VENTANA_THROTTLE_MINUTOS))) {
            return Optional.empty();
        }

        String tokenCrudo = generarTokenCrudo();
        UsuarioToken nuevoToken = new UsuarioToken();
        nuevoToken.setUsuarioId(usuario.getId());
        nuevoToken.setTipo(TIPO_RECUPERACION);
        nuevoToken.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        nuevoToken.setExpiraEn(ahora.plusHours(EXPIRACION_HORAS));
        nuevoToken.setUsado(false);
        nuevoToken.setCreateDate(ahora);
        usuarioTokenRepository.save(nuevoToken);

        return Optional.of(new RecuperacionResultado(usuario.getEmail(), tokenCrudo));
    }

    /**
     * Restablece la contraseña en una única transacción atómica si el token es válido:
     * <ol>
     *   <li>Marca el token como {@code usado = true}</li>
     *   <li>Actualiza {@code usuario.passwordHash} con el BCrypt de {@code nuevaPassword}</li>
     *   <li>Limpia {@code intentosFallidos = 0} y {@code bloqueadaHasta = null}</li>
     *   <li>Revoca TODOS los refresh tokens activos del usuario</li>
     * </ol>
     *
     * @return {@code true} si el restablecimiento fue exitoso; {@code false} si el token
     *         no existe, está usado o expiró.
     */
    @Transactional
    public boolean restablecer(String tokenCrudo, String nuevaPassword) {
        String hash = TokenHasher.sha256Hex(tokenCrudo);
        Optional<UsuarioToken> tokenOpt = usuarioTokenRepository
                .findByTokenHashAndUsadoFalseAndExpiraEnAfter(hash, LocalDateTime.now());

        if (tokenOpt.isEmpty()) {
            return false;
        }

        UsuarioToken token = tokenOpt.get();
        Usuario usuario = usuarioRepository.findById(token.getUsuarioId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_token referencia un usuario_id inexistente: " + token.getUsuarioId()));

        LocalDateTime ahora = LocalDateTime.now();

        // Paso 1: marcar token como usado
        token.setUsado(true);
        usuarioTokenRepository.save(token);

        // Paso 2: actualizar password
        usuario.setPasswordHash(passwordEncoder.encode(nuevaPassword));
        // Paso 3: limpiar bloqueo
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadaHasta(null);
        usuario.setUpdateDate(ahora);
        usuarioRepository.save(usuario);

        // Paso 4: revocar todos los refresh tokens activos (usar token.getUsuarioId() porque
        // en un contexto de test unitario usuario.getId() puede ser null al no ser persistido)
        List<SesionRefreshToken> sesionesActivas = sesionRefreshTokenRepository
                .findByUsuarioIdAndRevocadoFalse(token.getUsuarioId());

        if (!sesionesActivas.isEmpty()) {
            sesionesActivas.forEach(sesion -> {
                sesion.setRevocado(true);
                sesion.setRevocadoEn(ahora);
            });
            sesionRefreshTokenRepository.saveAll(sesionesActivas);
        }

        return true;
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Resultado de una generación de token exitosa: email destino y token crudo para el enlace. */
    public record RecuperacionResultado(String email, String tokenCrudo) {
    }
}
