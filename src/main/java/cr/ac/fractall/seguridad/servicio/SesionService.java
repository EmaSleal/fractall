package cr.ac.fractall.seguridad.servicio;

import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Emisión de access tokens para un usuario ya autenticado, contra una empresa concreta:
 * {@code POST /auth/seleccionar-tenant} (sección 3.2 punto 2), {@code POST /auth/cambiar-tenant}
 * (punto 3) y {@code POST /auth/refrescar} (sección 2, refresh token revocable). Las tres
 * operaciones comparten el mismo chequeo -- membresía {@code ACTIVO} vigente para el par
 * usuario-empresa -- centralizado aquí por el mismo motivo que {@code LoginService} centraliza
 * el chequeo de credenciales: evitar que una futura cuarta ruta de acceso lo reimplemente.
 */
@Service
public class SesionService {

    private static final String ESTADO_ACTIVO = "ACTIVO";

    /** Único rol para el que MFA es obligatorio (sección 3.3) -- ver el comentario en {@link #seleccionarTenant}. */
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";

    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public SesionService(
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Selección de tenant tras un login de 2+ empresas: emite access token + refresh token
     * nuevos, salvo que la membresía resuelta sea {@code ADMIN_EMPRESA} (sección 3.3) -- en
     * ese caso, igual que en {@code LoginService#login} para el caso de una sola empresa, se
     * emite el token de alcance mínimo "MFA pendiente" en su lugar.
     */
    public SesionResultado seleccionarTenant(UUID usuarioId, UUID empresaId) {
        UsuarioEmpresa membresia = verificarMembresiaActiva(usuarioId, empresaId);

        if (esRolAdminEmpresa(membresia.getRolId())) {
            Usuario usuario = usuarioRepository.findById(usuarioId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Token de selección de tenant referencia un usuario_id inexistente: " + usuarioId));
            String tokenMfaPendiente = jwtService.generarTokenMfaPendiente(usuarioId, empresaId);
            return SesionResultado.requiereMfa(tokenMfaPendiente, !usuario.isMfaHabilitado());
        }

        String accessToken = jwtService.generarToken(usuarioId, empresaId);
        String refreshToken = refreshTokenService.emitir(usuarioId);
        return SesionResultado.completo(new TokensAcceso(accessToken, refreshToken, empresaId));
    }

    /**
     * Cambio de tenant en caliente (sección 3.2 punto 3): NO emite un refresh token nuevo ni
     * revoca el existente -- ver el comentario en {@code AuthController#cambiarTenant} sobre
     * por qué no hay nada que invalidar server-side del access token anterior.
     */
    public TokensAcceso cambiarTenant(UUID usuarioId, UUID empresaId) {
        verificarMembresiaActiva(usuarioId, empresaId);
        String accessToken = jwtService.generarToken(usuarioId, empresaId);
        return new TokensAcceso(accessToken, null, empresaId);
    }

    /**
     * {@code POST /auth/refrescar}: NO rota el refresh token (rotación queda como
     * hardening futuro, fuera de alcance de este batch) -- solo emite un access token nuevo
     * tras reconfirmar la membresía, ya que {@code sesion_refresh_token} no tiene columna
     * {@code empresa_id} (está por diseño escopado al usuario, no a una empresa).
     */
    public TokensAcceso refrescar(String refreshTokenCrudo, UUID empresaId) {
        SesionRefreshToken sesion = refreshTokenService.validar(refreshTokenCrudo)
                .orElseThrow(RefreshTokenInvalidoException::new);
        verificarMembresiaActiva(sesion.getUsuarioId(), empresaId);
        String accessToken = jwtService.generarToken(sesion.getUsuarioId(), empresaId);
        return new TokensAcceso(accessToken, null, empresaId);
    }

    private UsuarioEmpresa verificarMembresiaActiva(UUID usuarioId, UUID empresaId) {
        return usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, ESTADO_ACTIVO)
                .orElseThrow(MembresiaInactivaException::new);
    }

    private boolean esRolAdminEmpresa(UUID rolId) {
        Rol rol = rolRepository.findById(rolId)
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_empresa referencia un rol_id inexistente: " + rolId));
        return ROL_ADMIN_EMPRESA.equals(rol.getCodigo());
    }
}
