package cr.ac.fractall.seguridad.servicio;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.seguridad.dto.MiembroResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository.MiembroProyeccion;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Administración de membresías de una empresa (Fase B -- ver design.md, sección
 * {@code MembresiaAdminService}). Cada método propio arranca con
 * {@link PermisoGuard#exigir(UUID, UUID, String)} contra el permiso de negocio correspondiente,
 * nunca contra un string de rol.
 */
@Service
public class MembresiaAdminService {

    private static final String PERMISO_VER = "usuario.ver";
    private static final String PERMISO_EDITAR_ROL = "usuario.editar_rol";
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";
    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final PermisoGuard permisoGuard;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;

    public MembresiaAdminService(
            PermisoGuard permisoGuard,
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository) {
        this.permisoGuard = permisoGuard;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
    }

    /**
     * Lista todas las membresías de {@code empresaId} (cualquier estado -- {@code ACTIVO},
     * {@code INVITACION_PENDIENTE}, {@code SUSPENDIDO} -- ver requerimiento "Membership Listing"
     * de spec.md), nunca de otra empresa: {@code empresaId} viene siempre de
     * {@code TenantContext}, resuelto por el controlador, nunca de un parámetro de la solicitud.
     */
    public List<MiembroResponse> listar(UUID actorId, UUID empresaId) {
        permisoGuard.exigir(actorId, empresaId, PERMISO_VER);

        return usuarioEmpresaRepository.listarMiembros(empresaId).stream()
                .map(this::aRespuesta)
                .toList();
    }

    /**
     * Cambia el rol de una membresía de la empresa actual del actor (Fase B, PR5b -- ver
     * design.md, sección "MembresiaAdminService"). Orden deliberado: permiso -&gt; existencia del
     * objetivo (404) -&gt; validez del rol nuevo (400) -&gt; autogestión (409, mensaje más
     * específico y una consulta menos) -&gt; último administrador (409).
     *
     * <p>El hook MFA (decisión E, que corrige la D4 del proposal original) SOLO marca el flag
     * persistente: el actor es el administrador que promueve, no el promovido, así que no existe
     * ningún token MFA de otra persona que se le pueda devolver aquí -- el promovido queda
     * bloqueado en su siguiente login por {@code LoginService}/{@code SesionService}.
     */
    @Transactional
    public MiembroResponse cambiarRol(UUID actorId, UUID empresaId, UUID objetivoId, String rolCodigo) {
        permisoGuard.exigir(actorId, empresaId, PERMISO_EDITAR_ROL);

        UsuarioEmpresa objetivo = resolverObjetivo(empresaId, objetivoId);
        Rol rolNuevo = resolverRol(rolCodigo);

        exigirNoAutogestion(actorId, objetivoId);
        exigirNoUltimoAdministrador(empresaId, objetivo, rolNuevo.getId());

        objetivo.setRolId(rolNuevo.getId());
        usuarioEmpresaRepository.save(objetivo);

        if (ROL_ADMIN_EMPRESA.equals(rolNuevo.getCodigo())) {
            marcarMfaRequerido(objetivo.getUsuarioId());
        }

        return aRespuesta(objetivo, rolNuevo.getCodigo());
    }

    /**
     * Resuelve la membresía objetivo acotada al par (usuarioId, empresaId) del ACTOR -- nunca
     * por {@code usuarioId} suelto, para no filtrar membresías de otra empresa (evita IDOR/fuga
     * cross-tenant, ver {@code AislamientoMultiTenantTest}). Ver también el javadoc de
     * {@code UsuarioEmpresaRepository#findByUsuarioIdAndEmpresaId} sobre por qué incluye
     * cualquier estado.
     */
    private UsuarioEmpresa resolverObjetivo(UUID empresaId, UUID objetivoId) {
        return usuarioEmpresaRepository.findByUsuarioIdAndEmpresaId(objetivoId, empresaId)
                .orElseThrow(MiembroNoEncontradoException::new);
    }

    private Rol resolverRol(String rolCodigo) {
        return rolRepository.findByCodigo(rolCodigo)
                .orElseThrow(() -> new RolInvitacionInvalidoException(rolCodigo));
    }

    /**
     * Compartida con {@code suspender} (PR5c) -- ver design.md, sección "MembresiaAdminService".
     */
    private void exigirNoAutogestion(UUID actorId, UUID objetivoId) {
        if (actorId.equals(objetivoId)) {
            throw new AutoGestionNoPermitidaException();
        }
    }

    /**
     * Compartida con {@code suspender} (PR5c, que la invocará con {@code rolNuevoId = null} --
     * la membresía se suspende, no cambia de rol). No-op salvo que el objetivo sea
     * {@code ADMIN_EMPRESA} ACTIVO y la operación lo saque de esa condición ({@code rolNuevoId}
     * distinto de ADMIN_EMPRESA, o {@code null}). En ese caso, rechaza si queda 1 o menos
     * administradores activos ANTES del cambio -- el propio objetivo cuenta en ese conteo.
     */
    private void exigirNoUltimoAdministrador(UUID empresaId, UsuarioEmpresa objetivo, UUID rolNuevoId) {
        UUID adminRolId = rolRepository.findByCodigo(ROL_ADMIN_EMPRESA).orElseThrow().getId();
        boolean eraAdminActivo = adminRolId.equals(objetivo.getRolId()) && ESTADO_ACTIVO.equals(objetivo.getEstado());
        boolean dejaDeSerAdmin = rolNuevoId == null || !adminRolId.equals(rolNuevoId);

        if (eraAdminActivo && dejaDeSerAdmin
                && usuarioEmpresaRepository.contarAdministradoresActivos(empresaId) <= 1) {
            throw new UltimoAdministradorException();
        }
    }

    private void marcarMfaRequerido(UUID usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId).orElseThrow();
        usuario.setMfaRequerido(true);
        usuarioRepository.save(usuario);
    }

    private MiembroResponse aRespuesta(MiembroProyeccion proyeccion) {
        return new MiembroResponse(
                proyeccion.getUsuarioId(),
                proyeccion.getNombre(),
                proyeccion.getEmail(),
                proyeccion.getRolCodigo(),
                proyeccion.getEstado(),
                proyeccion.getFechaIngreso());
    }

    private MiembroResponse aRespuesta(UsuarioEmpresa membresia, String rolCodigo) {
        Usuario usuario = usuarioRepository.findById(membresia.getUsuarioId()).orElseThrow();
        return new MiembroResponse(
                usuario.getId(),
                usuario.getNombre(),
                usuario.getEmail(),
                rolCodigo,
                membresia.getEstado(),
                membresia.getFechaIngreso());
    }
}
