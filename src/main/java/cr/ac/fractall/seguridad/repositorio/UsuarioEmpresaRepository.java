package cr.ac.fractall.seguridad.repositorio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;

public interface UsuarioEmpresaRepository extends JpaRepository<UsuarioEmpresa, UUID> {

    /**
     * Membresías activas de un usuario -- usado por {@code LoginService} para decidir si el
     * login emite un access token completo (exactamente 1) o el token de selección de
     * tenant (2+), sección 3.2 del documento de arquitectura.
     */
    List<UsuarioEmpresa> findByUsuarioIdAndEstado(UUID usuarioId, String estado);

    /**
     * Verifica una membresía activa puntual para un par usuario-empresa -- usado por
     * {@code SesionService} en {@code seleccionar-tenant}, {@code cambiar-tenant} y
     * {@code refrescar}, los tres puntos donde se debe reconfirmar la membresía antes de
     * emitir un access token para esa empresa.
     */
    Optional<UsuarioEmpresa> findByUsuarioIdAndEmpresaIdAndEstado(UUID usuarioId, UUID empresaId, String estado);
}
