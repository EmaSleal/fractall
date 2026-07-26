package cr.ac.fractall.seguridad.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;

/**
 * Revocación de sesiones para {@code POST /auth/logout}. Sin
 * {@code TenantContextDescartable} (endpoint autenticado -- {@code JwtTenantFilter} ya
 * estableció el contexto antes de llegar aquí).
 *
 * <p>La revocación es idempotente: un token ya revocado o inexistente no genera error -- el
 * endpoint siempre responde 200.
 */
@Service
public class LogoutService {

    private final SesionRefreshTokenRepository sesionRefreshTokenRepository;

    public LogoutService(SesionRefreshTokenRepository sesionRefreshTokenRepository) {
        this.sesionRefreshTokenRepository = sesionRefreshTokenRepository;
    }

    /**
     * Revoca sesiones del usuario según {@code todasLasSesiones}:
     * <ul>
     *   <li>{@code false} -- solo revoca el refresh token que coincide con {@code refreshTokenCrudo}</li>
     *   <li>{@code true} -- revoca TODOS los tokens activos del usuario</li>
     * </ul>
     * La operación es idempotente: si el token no se encuentra o ya está revocado, no hace nada.
     */
    @Transactional
    public void logout(String refreshTokenCrudo, boolean todasLasSesiones, UUID usuarioId) {
        LocalDateTime ahora = LocalDateTime.now();

        if (todasLasSesiones) {
            List<SesionRefreshToken> tokens = sesionRefreshTokenRepository
                    .findByUsuarioIdAndRevocadoFalse(usuarioId);

            if (tokens.isEmpty()) {
                return;
            }

            tokens.forEach(token -> {
                token.setRevocado(true);
                token.setRevocadoEn(ahora);
            });
            sesionRefreshTokenRepository.saveAll(tokens);
        } else {
            String hash = TokenHasher.sha256Hex(refreshTokenCrudo);
            Optional<SesionRefreshToken> tokenOpt = sesionRefreshTokenRepository
                    .findByTokenHashAndRevocadoFalseAndExpiraEnAfter(hash, ahora);

            tokenOpt.ifPresent(token -> {
                token.setRevocado(true);
                token.setRevocadoEn(ahora);
                sesionRefreshTokenRepository.save(token);
            });
        }
    }
}
