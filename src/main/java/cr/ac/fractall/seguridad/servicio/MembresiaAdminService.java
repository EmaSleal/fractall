package cr.ac.fractall.seguridad.servicio;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.seguridad.dto.MiembroResponse;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository.MiembroProyeccion;

/**
 * Administración de membresías de una empresa (Fase B, PR5a -- ver design.md, sección
 * {@code MembresiaAdminService}). Cada método propio arranca con
 * {@link PermisoGuard#exigir(UUID, UUID, String)} contra el permiso de negocio correspondiente,
 * nunca contra un string de rol.
 */
@Service
public class MembresiaAdminService {

    private static final String PERMISO_VER = "usuario.ver";

    private final PermisoGuard permisoGuard;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;

    public MembresiaAdminService(PermisoGuard permisoGuard, UsuarioEmpresaRepository usuarioEmpresaRepository) {
        this.permisoGuard = permisoGuard;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
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

    private MiembroResponse aRespuesta(MiembroProyeccion proyeccion) {
        return new MiembroResponse(
                proyeccion.getUsuarioId(),
                proyeccion.getNombre(),
                proyeccion.getEmail(),
                proyeccion.getRolCodigo(),
                proyeccion.getEstado(),
                proyeccion.getFechaIngreso());
    }
}
