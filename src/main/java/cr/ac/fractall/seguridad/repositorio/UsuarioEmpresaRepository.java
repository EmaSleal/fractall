package cr.ac.fractall.seguridad.repositorio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Permisos efectivos del usuario en la empresa indicada, leídos desde la vista
     * {@code permisos_efectivos} (V3). La vista está indexada por {@code usuario_empresa_id},
     * y se accede a ella vía JOIN con {@code usuario_empresa} para filtrar por el par
     * (usuarioId, empresaId) -- la vista en sí no contiene esas columnas directamente.
     *
     * <p>Native query obligatorio porque {@code permisos_efectivos} es una vista sin entidad
     * JPA mapeada; JPQL no puede referenciarla directamente.
     */
    @Query(value = """
            SELECT pe.permiso_codigo FROM permisos_efectivos pe
            JOIN usuario_empresa ue ON ue.id = pe.usuario_empresa_id
            WHERE ue.usuario_id = :usuarioId AND ue.empresa_id = :empresaId
            """, nativeQuery = true)
    List<String> findPermisoCodigos(@Param("usuarioId") UUID usuarioId, @Param("empresaId") UUID empresaId);

    /**
     * Listado de {@code GET /usuarios} (Fase B, PR5a -- ver design.md). {@code UsuarioEmpresa} no
     * extiende {@code TenantAwareEntity}, así que {@code empresaId} se filtra explícitamente (ver
     * javadoc de {@code ClienteRepository}). Native query obligatorio por el mismo motivo que
     * {@code findPermisoCodigos}: {@code Usuario}, {@code Rol} y {@code UsuarioEmpresa} se
     * relacionan por columnas UUID sueltas, sin {@code @ManyToOne} mapeado -- JPQL no puede
     * hacer el join.
     */
    @Query(value = """
            SELECT ue.usuario_id AS usuarioId, u.nombre AS nombre, u.email AS email,
                   r.codigo AS rolCodigo, ue.estado AS estado, ue.fecha_ingreso AS fechaIngreso
            FROM usuario_empresa ue
            JOIN usuario u ON u.id = ue.usuario_id
            JOIN rol     r ON r.id = ue.rol_id
            WHERE ue.empresa_id = :empresaId
            ORDER BY u.nombre
            """, nativeQuery = true)
    List<MiembroProyeccion> listarMiembros(@Param("empresaId") UUID empresaId);

    interface MiembroProyeccion {
        UUID getUsuarioId();

        String getNombre();

        String getEmail();

        String getRolCodigo();

        String getEstado();

        java.time.LocalDateTime getFechaIngreso();
    }

    /**
     * Objetivo de cambio de rol / suspensión (Fase B, PR5b -- ver design.md, sección
     * "MembresiaAdminService"). Incluye a propósito membresías en cualquier estado
     * ({@code INVITACION_PENDIENTE}, {@code SUSPENDIDO}), no solo {@code ACTIVO}: un
     * administrador debe poder cambiar el rol de una membresía todavía pendiente o ya
     * suspendida sin necesitar un endpoint distinto para eso.
     */
    Optional<UsuarioEmpresa> findByUsuarioIdAndEmpresaId(UUID usuarioId, UUID empresaId);

    /**
     * Guarda del último administrador (Fase B, PR5b -- ver design.md, decisión de diseño
     * {@code exigirNoUltimoAdministrador}): cuántas membresías {@code ADMIN_EMPRESA} ACTIVAS
     * quedan en la empresa. Native query obligatorio por el mismo motivo que
     * {@code findPermisoCodigos}/{@code listarMiembros}: {@code usuario_empresa} y {@code rol}
     * se relacionan por una columna UUID suelta, sin {@code @ManyToOne} mapeado -- JPQL no
     * puede hacer el join.
     */
    @Query(value = """
            SELECT COUNT(*) FROM usuario_empresa ue
            JOIN rol r ON r.id = ue.rol_id
            WHERE ue.empresa_id = :empresaId AND r.codigo = 'ADMIN_EMPRESA' AND ue.estado = 'ACTIVO'
            """, nativeQuery = true)
    long contarAdministradoresActivos(@Param("empresaId") UUID empresaId);
}
