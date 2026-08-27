package cr.ac.fractall.seguridad.servicio;

import java.util.UUID;

import org.springframework.stereotype.Component;

import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;

/**
 * Guard de autorización para los endpoints de {@code /usuarios/*} (Fase B, invitación y
 * administración de membresías): falla cerrado con {@link PermisoDenegadoException} si la
 * membresía del actor no está ACTIVA en la empresa objetivo, o si el permiso solicitado no
 * aparece en {@code permisos_efectivos} para esa membresía.
 *
 * <p>El chequeo de estado NO es redundante con la lectura de permisos: la vista
 * {@code permisos_efectivos} (V3:71-90) arranca en {@code usuario_empresa} SIN filtrar
 * {@code ue.estado}, así que una membresía SUSPENDIDO o INVITACION_PENDIENTE resuelve
 * igualmente el catálogo completo de permisos de su rol. Sin este chequeo explícito, suspender
 * a un administrador no tendría ningún efecto sobre estos endpoints -- ver diseño, sección
 * "Blocking discoveries", punto 2.
 */
@Component
public class PermisoGuard {

    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final UsuarioEmpresaRepository usuarioEmpresaRepository;

    public PermisoGuard(UsuarioEmpresaRepository usuarioEmpresaRepository) {
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
    }

    public void exigir(UUID usuarioId, UUID empresaId, String permisoCodigo) {
        usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, ESTADO_ACTIVO)
                .orElseThrow(PermisoDenegadoException::new);

        if (!usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId).contains(permisoCodigo)) {
            throw new PermisoDenegadoException();
        }
    }
}
