package cr.ac.fractall.seguridad.servicio;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.EmpresaResumenResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;

/**
 * Lista las empresas activas ({@code estado = 'ACTIVO'}) del usuario autenticado para
 * {@code GET /auth/mis-empresas}. Sin {@code @Transactional} (solo lectura) y sin
 * {@code TenantContextDescartable} (endpoint autenticado -- {@code JwtTenantFilter} ya
 * estableció el contexto de tenant antes de llegar aquí).
 */
@Service
public class MisEmpresasService {

    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final EmpresaRepository empresaRepository;
    private final RolRepository rolRepository;

    public MisEmpresasService(
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            EmpresaRepository empresaRepository,
            RolRepository rolRepository) {
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.empresaRepository = empresaRepository;
        this.rolRepository = rolRepository;
    }

    /**
     * Retorna todas las membresías activas del usuario, mapeadas a su resumen de empresa.
     * Una lista vacía es una respuesta válida (usuario sin empresas activas).
     */
    public List<EmpresaResumenResponse> listar(UUID usuarioId) {
        List<UsuarioEmpresa> membresias = usuarioEmpresaRepository
                .findByUsuarioIdAndEstado(usuarioId, ESTADO_ACTIVO);

        return membresias.stream()
                .map(membresia -> mapearAResumen(membresia))
                .toList();
    }

    private EmpresaResumenResponse mapearAResumen(UsuarioEmpresa membresia) {
        Empresa empresa = empresaRepository.findById(membresia.getEmpresaId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_empresa referencia un empresa_id inexistente: " + membresia.getEmpresaId()));

        Rol rol = rolRepository.findById(membresia.getRolId())
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_empresa referencia un rol_id inexistente: " + membresia.getRolId()));

        return new EmpresaResumenResponse(
                membresia.getEmpresaId(),
                empresa.getRazonSocial(),
                empresa.getNombreComercial(),
                rol.getCodigo(),
                membresia.getEstado());
    }
}
