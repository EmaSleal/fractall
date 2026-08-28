package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.modelo.UsuarioToken;
import cr.ac.fractall.seguridad.dto.RegistroPorInvitacionRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.repositorio.InvitacionUsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;

/**
 * Registro transaccional de {@code usuario} + {@code empresa} + {@code usuario_empresa}
 * (sección 3.1, punto 1). Atomicidad obligatoria: si cualquier paso falla, todo el método
 * hace rollback -- nunca debe quedar un {@code usuario} huérfano sin su {@code empresa}.
 *
 * <p>El llamador (ver {@code AuthController}) es responsable de fijar
 * {@link cr.ac.fractall.tenant.TenantContext} ANTES de invocar {@link #registrar}, vía
 * {@link cr.ac.fractall.tenant.TenantContextDescartable} -- este método corre detrás de un
 * endpoint público sin JWT, así que nunca hay un tenant real que resolver todavía.
 *
 * <p>El envío del correo de verificación queda deliberadamente FUERA de este método: el
 * llamador debe invocarlo solo después de que esta transacción ya hizo commit, para que un
 * fallo de Resend nunca revierta el registro (ver {@code AuthController#registrar}).
 */
@Service
public class RegistroService {

    /** 256 bits de entropía -- SecureRandom, no UUID (sección 3.1: "token aleatorio criptográficamente seguro"). */
    private static final int TOKEN_BYTES = 32;

    /**
     * 24h: el documento de arquitectura sugiere un rango de 24-48h para la expiración del
     * token de verificación de correo; se elige el extremo más corto para minimizar la
     * ventana en la que un enlace interceptado (ej. en un correo reenviado sin querer)
     * sigue siendo válido.
     */
    private static final long EXPIRACION_HORAS = 24;

    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";

    private static final String ESTADO_USUARIO_ACTIVA = "ACTIVA";
    private static final String ESTADO_USUARIO_PENDIENTE_VERIFICACION = "PENDIENTE_VERIFICACION";
    private static final String ESTADO_MEMBRESIA_ACTIVO = "ACTIVO";
    private static final String ESTADO_INVITACION_ACEPTADA = "ACEPTADA";

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final RolRepository rolRepository;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final UsuarioTokenRepository usuarioTokenRepository;
    private final InvitacionUsuarioService invitacionUsuarioService;
    private final InvitacionUsuarioRepository invitacionUsuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistroService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            RolRepository rolRepository,
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            UsuarioTokenRepository usuarioTokenRepository,
            InvitacionUsuarioService invitacionUsuarioService,
            InvitacionUsuarioRepository invitacionUsuarioRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.rolRepository = rolRepository;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.usuarioTokenRepository = usuarioTokenRepository;
        this.invitacionUsuarioService = invitacionUsuarioService;
        this.invitacionUsuarioRepository = invitacionUsuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistroResultado registrar(RegistroRequest request) {
        // Normalizado aquí (no solo a nivel de consulta en UsuarioRepository) para que todo
        // registro nuevo quede almacenado en una única convención -- evita que
        // "Test@x.com" y "test@x.com" se traten como cuentas distintas.
        String email = request.email().trim().toLowerCase();

        if (usuarioRepository.findByEmail(email).isPresent()) {
            throw new RegistroDuplicadoException("Ya existe una cuenta registrada con ese correo");
        }

        Rol rolAdminEmpresa = rolRepository.findByCodigo(ROL_ADMIN_EMPRESA)
                .orElseThrow(() -> new IllegalStateException(
                        "Rol " + ROL_ADMIN_EMPRESA + " no encontrado -- revisar migraciones de Fase 0 (V1__esquema_base.sql)"));

        LocalDateTime ahora = LocalDateTime.now();

        boolean mfaRequerido = request.activarMfa() == null || request.activarMfa();
        Usuario usuario = nuevoUsuario(request.nombre(), email, request.password(), mfaRequerido, false, ahora);

        Empresa empresa = new Empresa();
        empresa.setRazonSocial(request.razonSocial());
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(usuario.getId());
        empresa.setCreateDate(ahora);
        empresa.setUpdateDate(ahora);
        empresa = empresaRepository.save(empresa);

        nuevaMembresia(usuario.getId(), empresa.getId(), rolAdminEmpresa.getId(), ESTADO_MEMBRESIA_ACTIVO, null, ahora);

        String tokenCrudo = generarTokenCrudo();
        UsuarioToken tokenVerificacion = new UsuarioToken();
        tokenVerificacion.setUsuarioId(usuario.getId());
        tokenVerificacion.setTipo("VERIFICACION_EMAIL");
        tokenVerificacion.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        tokenVerificacion.setExpiraEn(ahora.plusHours(EXPIRACION_HORAS));
        tokenVerificacion.setUsado(false);
        tokenVerificacion.setCreateDate(ahora);
        usuarioTokenRepository.save(tokenVerificacion);

        return new RegistroResultado(usuario.getId(), empresa.getId(), usuario.getEmail(), tokenCrudo);
    }

    /**
     * Segundo punto de entrada, NO una rama dentro de {@link #registrar} (design.md, decisión
     * D3): la ruta de alta de empresa nueva es la ruta de ingresos y no debe cargar riesgo de
     * regresión de esta feature. NO crea {@code Empresa} (la invitación ya apunta a una
     * existente), NO asigna {@code ADMIN_EMPRESA} por defecto (usa el rol de la invitación) y
     * NO emite token de verificación de correo -- recibir el enlace de invitación en esa
     * casilla ya prueba control del buzón.
     *
     * <p>El correo del nuevo {@code usuario} se toma SIEMPRE de {@code invitacion.getEmail()},
     * nunca de {@code request} (que ni siquiera tiene ese campo -- ver
     * {@link RegistroPorInvitacionRequest}): un token válido no puede canjearse hacia otra
     * dirección.
     *
     * <p>Misma transacción que la validación del token ({@link InvitacionUsuarioService#consumirParaRegistro}
     * corre con propagación {@code REQUIRED}, se une a esta): crear {@code usuario} → crear
     * {@code usuario_empresa} {@code ACTIVO} con el {@code rol_id} e {@code invitado_por} de la
     * invitación → marcar la invitación {@code ACEPTADA}. Un fallo en cualquier paso deja la
     * invitación {@code PENDIENTE} y reintentable.
     */
    @Transactional
    public RegistroPorInvitacionResultado registrarPorInvitacion(RegistroPorInvitacionRequest request) {
        InvitacionUsuario invitacion = invitacionUsuarioService.consumirParaRegistro(request.invitacionToken());

        Rol rol = rolRepository.findById(invitacion.getRolId())
                .orElseThrow(() -> new IllegalStateException(
                        "invitacion_usuario referencia un rol_id inexistente: " + invitacion.getRolId()));

        LocalDateTime ahora = LocalDateTime.now();

        // ADMIN_EMPRESA siempre requiere MFA, sin importar la preferencia del invitado -- mismo
        // hook que InvitacionUsuarioService#aceptar aplica sobre una cuenta ya existente.
        boolean mfaRequerido = ROL_ADMIN_EMPRESA.equals(rol.getCodigo())
                || request.activarMfa() == null || request.activarMfa();

        Usuario usuario = nuevoUsuario(request.nombre(), invitacion.getEmail(), request.password(), mfaRequerido, true, ahora);

        nuevaMembresia(usuario.getId(), invitacion.getEmpresaId(), rol.getId(), ESTADO_MEMBRESIA_ACTIVO,
                invitacion.getInvitadoPor(), ahora);

        invitacion.setEstado(ESTADO_INVITACION_ACEPTADA);
        invitacionUsuarioRepository.save(invitacion);

        return new RegistroPorInvitacionResultado(usuario.getId(), invitacion.getEmpresaId(), rol.getCodigo());
    }

    /**
     * Compartido por {@link #registrar} y {@link #registrarPorInvitacion} (design.md, decisión
     * D3): {@code verificado} deriva tanto {@code emailVerificado} como {@code estado} -- ambos
     * caminos hoy son mutuamente excluyentes en su valor ({@code false}/{@code PENDIENTE_VERIFICACION}
     * para alta estándar, {@code true}/{@code ACTIVA} para alta por invitación), así que no hace
     * falta un parámetro adicional para {@code estado}.
     */
    private Usuario nuevoUsuario(
            String nombre, String email, String password, boolean mfaRequerido, boolean verificado, LocalDateTime ahora) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setEmail(email);
        usuario.setPasswordHash(passwordEncoder.encode(password));
        usuario.setEmailVerificado(verificado);
        usuario.setEstado(verificado ? ESTADO_USUARIO_ACTIVA : ESTADO_USUARIO_PENDIENTE_VERIFICACION);
        usuario.setMfaHabilitado(false);
        usuario.setMfaRequerido(mfaRequerido);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(ahora);
        usuario.setUpdateDate(ahora);
        return usuarioRepository.save(usuario);
    }

    /**
     * Compartido por {@link #registrar} ({@code invitadoPor} siempre {@code null}: nadie invitó
     * al fundador de su propia empresa) y {@link #registrarPorInvitacion} ({@code invitadoPor}
     * viene de {@code invitacion.getInvitadoPor()}).
     */
    private UsuarioEmpresa nuevaMembresia(
            UUID usuarioId, UUID empresaId, UUID rolId, String estado, UUID invitadoPor, LocalDateTime ahora) {
        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rolId);
        membresia.setEstado(estado);
        membresia.setInvitadoPor(invitadoPor);
        membresia.setFechaIngreso(ahora);
        return usuarioEmpresaRepository.save(membresia);
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code tokenCrudo} es de un solo uso por el llamador: enviarlo por correo y descartarlo. */
    public record RegistroResultado(UUID usuarioId, UUID empresaId, String email, String tokenCrudo) {
    }

    /** Resultado de {@link #registrarPorInvitacion}, para encadenar {@code SesionService.seleccionarTenant}. */
    public record RegistroPorInvitacionResultado(UUID usuarioId, UUID empresaId, String rolCodigo) {
    }
}
