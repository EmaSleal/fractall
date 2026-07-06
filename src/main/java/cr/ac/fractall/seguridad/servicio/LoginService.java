package cr.ac.fractall.seguridad.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * {@code POST /auth/login} (sección 3.2 punto 1, y sección 3.4 bloqueo por fuerza bruta).
 * Toda la lógica de credenciales + bloqueo vive en este único método -- el documento de
 * arquitectura advierte explícitamente contra duplicarla si en el futuro se agrega un
 * segundo punto de entrada (ej. API key).
 *
 * <p>Deliberadamente SIN {@code @Transactional} a nivel de método: a diferencia de
 * {@code RegistroService} (que necesita atomicidad real entre usuario+empresa+membresia),
 * aquí un intento fallido debe persistir su incremento de {@code intentos_fallidos} AUNQUE
 * el método termine lanzando una excepción para señalar el rechazo al controlador. Si este
 * método fuera {@code @Transactional}, esa excepción marcaría toda la transacción como
 * rollback-only y el incremento se perdería -- justo el escenario que rompería el bloqueo
 * por fuerza bruta (el 5to intento nunca se guardaría). Cada {@code save()} de Spring Data
 * ya es transaccional por su cuenta (ver {@code SimpleJpaRepository}), así que cada
 * escritura individual de este método se compromete de forma independiente.
 */
@Service
public class LoginService {

    private static final int MAX_INTENTOS_FALLIDOS = 5;
    private static final long BLOQUEO_MINUTOS = 15;

    private static final String ESTADO_PENDIENTE_VERIFICACION = "PENDIENTE_VERIFICACION";
    private static final String ESTADO_ACTIVO = "ACTIVO";

    /** Único rol para el que MFA es obligatorio (sección 3.3) -- ver el comentario en {@link #login}. */
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";

    private final UsuarioRepository usuarioRepository;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public LoginService(
            UsuarioRepository usuarioRepository,
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            RolRepository rolRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginResultado login(String email, String password) {
        String normalizado = email.trim().toLowerCase();
        Usuario usuario = usuarioRepository.findByEmail(normalizado)
                .orElseThrow(CredencialesInvalidasException::new);

        if (ESTADO_PENDIENTE_VERIFICACION.equals(usuario.getEstado())) {
            throw new CuentaNoVerificadaException();
        }

        LocalDateTime ahora = LocalDateTime.now();
        if (usuario.getBloqueadaHasta() != null && usuario.getBloqueadaHasta().isAfter(ahora)) {
            throw new CuentaBloqueadaException();
        }

        if (!passwordEncoder.matches(password, usuario.getPasswordHash())) {
            registrarIntentoFallido(usuario, ahora);
            throw new CredencialesInvalidasException();
        }

        usuario.setIntentosFallidos(0);
        usuario.setBloqueadaHasta(null);
        usuario.setUltimoLogin(ahora);
        usuario.setUpdateDate(ahora);
        usuarioRepository.save(usuario);

        List<UsuarioEmpresa> membresiasActivas = usuarioEmpresaRepository
                .findByUsuarioIdAndEstado(usuario.getId(), ESTADO_ACTIVO);

        if (membresiasActivas.isEmpty()) {
            // No debería ser alcanzable hoy (el registro siempre crea 1 membresía), pero se
            // maneja de forma explícita en vez de fallar de forma críptica más adelante.
            throw new SinEmpresaActivaException();
        }

        if (membresiasActivas.size() == 1) {
            UsuarioEmpresa membresia = membresiasActivas.get(0);
            UUID empresaId = membresia.getEmpresaId();

            // MFA obligatorio para ADMIN_EMPRESA (sección 3.3) -- en vez del access token
            // completo, se emite el token de alcance mínimo "MFA pendiente": la contraseña ya
            // se validó y la empresa ya está resuelta, pero falta completar (mfaHabilitado
            // false) o confirmar (true) el segundo factor antes de que exista una sesión real.
            if (esRolAdminEmpresa(membresia.getRolId())) {
                String tokenMfaPendiente = jwtService.generarTokenMfaPendiente(usuario.getId(), empresaId);
                return LoginResultado.requiereMfa(tokenMfaPendiente, !usuario.isMfaHabilitado());
            }

            String accessToken = jwtService.generarToken(usuario.getId(), empresaId);
            String refreshToken = refreshTokenService.emitir(usuario.getId());
            return LoginResultado.completo(new TokensAcceso(accessToken, refreshToken, empresaId));
        }

        // 2+ empresas: solo el token de alcance mínimo, sin refresh token todavía -- el
        // refresh token se emite recién cuando /auth/seleccionar-tenant resuelve una empresa
        // concreta (sección 3.2, punto 2).
        String tokenSeleccionTenant = jwtService.generarTokenSeleccionTenant(usuario.getId());
        return LoginResultado.requiereSeleccionTenant(tokenSeleccionTenant);
    }

    private boolean esRolAdminEmpresa(UUID rolId) {
        Rol rol = rolRepository.findById(rolId)
                .orElseThrow(() -> new IllegalStateException(
                        "usuario_empresa referencia un rol_id inexistente: " + rolId));
        return ROL_ADMIN_EMPRESA.equals(rol.getCodigo());
    }

    private void registrarIntentoFallido(Usuario usuario, LocalDateTime ahora) {
        int intentos = usuario.getIntentosFallidos() + 1;
        usuario.setIntentosFallidos(intentos);
        if (intentos >= MAX_INTENTOS_FALLIDOS) {
            usuario.setBloqueadaHasta(ahora.plusMinutes(BLOQUEO_MINUTOS));
        }
        usuario.setUpdateDate(ahora);
        usuarioRepository.save(usuario);
    }

    /**
     * Exactamente uno de los tres pares/valor está poblado, según
     * {@code requiereSeleccionTenant} / {@code requiereMfa}. Se prefiere esta forma simple
     * (sin jerarquía sellada) consistente con el resto de resultados de servicio del paquete
     * (ej. {@code RegistroService.RegistroResultado}).
     */
    public record LoginResultado(
            boolean requiereSeleccionTenant,
            boolean requiereMfa,
            TokensAcceso tokens,
            String tokenSeleccionTenant,
            String tokenMfaPendiente,
            boolean mfaRequiereEnrolamiento) {

        static LoginResultado completo(TokensAcceso tokens) {
            return new LoginResultado(false, false, tokens, null, null, false);
        }

        static LoginResultado requiereSeleccionTenant(String tokenSeleccionTenant) {
            return new LoginResultado(true, false, null, tokenSeleccionTenant, null, false);
        }

        static LoginResultado requiereMfa(String tokenMfaPendiente, boolean mfaRequiereEnrolamiento) {
            return new LoginResultado(false, true, null, null, tokenMfaPendiente, mfaRequiereEnrolamiento);
        }
    }
}
