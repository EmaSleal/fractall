package cr.ac.fractall.seguridad.servicio;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.EmpresaResumenResponse;
import cr.ac.fractall.seguridad.dto.PerfilResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Perfil del usuario autenticado para {@code GET /auth/perfil}: datos personales, empresa
 * activa (del claim JWT) y permisos efectivos en esa empresa. Sin {@code @Transactional}
 * (solo lectura) y sin {@code TenantContextDescartable} (endpoint autenticado).
 */
@Service
public class PerfilService {

    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final RolRepository rolRepository;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;

    public PerfilService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            RolRepository rolRepository,
            UsuarioEmpresaRepository usuarioEmpresaRepository) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.rolRepository = rolRepository;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
    }

    /**
     * Carga el perfil del usuario para el par (usuarioId, empresaId) extraído del JWT.
     * {@code empresaId} viene del claim JWT, no del SecurityContext (ver diseño).
     */
    public PerfilResponse obtener(UUID usuarioId, UUID empresaId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Usuario autenticado no encontrado en base de datos: " + usuarioId));

        UsuarioEmpresa membresia = usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, ESTADO_ACTIVO)
                .orElseThrow(() -> new IllegalStateException(
                        "Membresía activa no encontrada para usuario=" + usuarioId + " empresa=" + empresaId));

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "Empresa no encontrada: " + empresaId));

        Rol rol = rolRepository.findById(membresia.getRolId())
                .orElseThrow(() -> new IllegalStateException(
                        "Rol no encontrado: " + membresia.getRolId()));

        List<String> permisos = usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId);

        EmpresaResumenResponse empresaResumen = new EmpresaResumenResponse(
                empresaId,
                empresa.getRazonSocial(),
                empresa.getNombreComercial(),
                rol.getCodigo(),
                membresia.getEstado());

        return new PerfilResponse(
                usuarioId,
                usuario.getNombre(),
                usuario.getEmail(),
                usuario.isMfaHabilitado(),
                empresaResumen,
                permisos);
    }
}
