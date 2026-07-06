package cr.ac.fractall.seguridad.repositorio;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.seguridad.modelo.UsuarioToken;

public interface UsuarioTokenRepository extends JpaRepository<UsuarioToken, UUID> {

    Optional<UsuarioToken> findByTokenHashAndUsadoFalseAndExpiraEnAfter(String tokenHash, LocalDateTime ahora);

    /**
     * Token de verificación de correo más reciente para un usuario, sin importar si ya fue
     * usado o expiró -- usado como señal de "último reenvío" para el límite de tasa por
     * email de {@code POST /auth/reenviar-verificacion} (5 minutos, ver sección 3.1). No se
     * crea una tabla nueva solo para este contador; se reutiliza {@code create_date} del
     * último token emitido.
     */
    Optional<UsuarioToken> findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(UUID usuarioId, String tipo);
}
