package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;

/**
 * Emisión y validación de refresh tokens revocables en base de datos ({@code sesion_refresh_token},
 * sección 2 y 4.0 del documento de arquitectura: "Access token de vida corta (10-15 min) +
 * refresh token revocable en base de datos").
 *
 * <p>Genera el token crudo con el mismo patrón que {@code RegistroService}/{@code
 * VerificacionEmailService} (SecureRandom de 32 bytes + base64url) pero SIN extraer un
 * helper compartido: esos dos servicios pertenecen al batch de registro/verificación ya
 * cerrado (fuera de alcance tocar en este batch), y la duplicación de estas 4 líneas es
 * mínima frente al riesgo de modificar código ya probado de otro batch. Si aparece un cuarto
 * caso de uso, ese es el momento de extraer {@code TokenCrudoGenerator}.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    /**
     * 7 días: el documento de arquitectura no fija un valor exacto para refresh tokens (a
     * diferencia del access token, 10-15 min explícitos). Se elige 7 días como default común
     * para este patrón (access corto + refresh revocable): suficientemente largo para evitar
     * re-login constante, aceptable porque el propio diseño ya provee revocación server-side
     * vía {@code sesion_refresh_token.revocado} -- a diferencia del access token, que no
     * tiene blacklist y depende solo de su corta expiración.
     */
    private static final long EXPIRACION_DIAS = 7;

    private final SesionRefreshTokenRepository sesionRefreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(SesionRefreshTokenRepository sesionRefreshTokenRepository) {
        this.sesionRefreshTokenRepository = sesionRefreshTokenRepository;
    }

    /** Emite y persiste un nuevo refresh token para el usuario; devuelve el token CRUDO. */
    public String emitir(UUID usuarioId) {
        String tokenCrudo = generarTokenCrudo();
        LocalDateTime ahora = LocalDateTime.now();

        SesionRefreshToken sesion = new SesionRefreshToken();
        sesion.setUsuarioId(usuarioId);
        sesion.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        sesion.setEmitidoEn(ahora);
        sesion.setExpiraEn(ahora.plusDays(EXPIRACION_DIAS));
        sesion.setRevocado(false);
        sesionRefreshTokenRepository.save(sesion);

        return tokenCrudo;
    }

    /**
     * {@link Optional#empty()} para CUALQUIER motivo de rechazo (hash inexistente, revocado,
     * expirado) -- el llamador no debe distinguir el motivo en la respuesta HTTP, mismo
     * criterio anti-enumeración que {@code VerificacionEmailService#verificar}.
     */
    public Optional<SesionRefreshToken> validar(String tokenCrudo) {
        String hash = TokenHasher.sha256Hex(tokenCrudo);
        return sesionRefreshTokenRepository.findByTokenHashAndRevocadoFalseAndExpiraEnAfter(hash, LocalDateTime.now());
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
