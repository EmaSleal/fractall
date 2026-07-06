package cr.ac.fractall.seguridad.repositorio;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;

public interface SesionRefreshTokenRepository extends JpaRepository<SesionRefreshToken, UUID> {

    /**
     * Busca una sesión de refresh token vigente por su hash -- nunca se consulta por el
     * token crudo (sección 4.0: {@code token_hash} es lo único que se persiste). Vigente
     * significa no revocado y no expirado; cualquier otro caso (hash inexistente, revocado,
     * expirado) debe tratarse como "refresh token inválido" sin distinguir el motivo.
     */
    Optional<SesionRefreshToken> findByTokenHashAndRevocadoFalseAndExpiraEnAfter(String tokenHash, LocalDateTime ahora);
}
