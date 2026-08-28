package cr.ac.fractall.seguridad.servicio;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.InvitacionUsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Emisión de invitaciones de un {@code usuario} a una {@code empresa} (Fase B -- ver
 * design.md, sección "InvitacionUsuarioService" y su "Data Flow").
 *
 * <p>El correo se envía FUERA de este servicio, por el llamador, DESPUÉS de que
 * {@link #emitir} ya hizo commit -- mismo criterio que {@code RegistroService} (ver su
 * javadoc) y {@code AuthController#registrar}: un fallo de Resend nunca debe revertir la
 * invitación.
 */
@Service
public class InvitacionUsuarioService {

    /** 256 bits de entropía -- mismo criterio que {@code RegistroService#TOKEN_BYTES}. */
    private static final int TOKEN_BYTES = 32;

    /** 7 días (resolución de la ronda de preguntas de design.md). */
    private static final long EXPIRACION_DIAS = 7;

    private static final String ESTADO_PENDIENTE = "PENDIENTE";
    private static final String ESTADO_ACEPTADA = "ACEPTADA";
    private static final String ESTADO_EXPIRADA = "EXPIRADA";
    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final String ESTADO_INVITACION_PENDIENTE = "INVITACION_PENDIENTE";
    private static final String PERMISO_INVITAR = "usuario.invitar";
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";

    private final PermisoGuard permisoGuard;
    private final InvitacionUsuarioRepository invitacionUsuarioRepository;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioEmpresaRepository usuarioEmpresaRepository;
    private final RolRepository rolRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public InvitacionUsuarioService(
            PermisoGuard permisoGuard,
            InvitacionUsuarioRepository invitacionUsuarioRepository,
            UsuarioRepository usuarioRepository,
            UsuarioEmpresaRepository usuarioEmpresaRepository,
            RolRepository rolRepository) {
        this.permisoGuard = permisoGuard;
        this.invitacionUsuarioRepository = invitacionUsuarioRepository;
        this.usuarioRepository = usuarioRepository;
        this.usuarioEmpresaRepository = usuarioEmpresaRepository;
        this.rolRepository = rolRepository;
    }

    /**
     * Falla cerrado con {@link PermisoDenegadoException} si el actor no tiene
     * {@code usuario.invitar} en {@code empresaId} (ver {@link PermisoGuard}).
     *
     * <p>Si ya existe una invitación {@code PENDIENTE} viva para el mismo correo+empresa, no
     * crea una segunda fila (el índice parcial único de V22 ya impone la regla a nivel de
     * motor; este chequeo evita incluso intentar el INSERT) y devuelve
     * {@link Optional#empty()} -- el llamador debe responder el mismo mensaje genérico sin
     * filtrar si la invitación se omitió o no (anti-enumeración, ver design.md).
     *
     * <p>Si el correo ya tiene una cuenta {@code usuario}, también crea la fila
     * {@code usuario_empresa} correspondiente en estado {@code INVITACION_PENDIENTE}
     * (requerimiento de negocio "Existing-user invite creates pending membership").
     */
    @Transactional
    public Optional<InvitacionEmitida> emitir(UUID actorId, UUID empresaId, String emailCrudo, String rolCodigo) {
        permisoGuard.exigir(actorId, empresaId, PERMISO_INVITAR);

        // Normalizado aquí, igual que RegistroService#registrar, para que "A@x.com" y
        // "a@x.com" nunca se traten como correos distintos.
        String email = emailCrudo.trim().toLowerCase();

        if (invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, email, ESTADO_PENDIENTE)
                .isPresent()) {
            return Optional.empty();
        }

        Rol rol = rolRepository.findByCodigo(rolCodigo)
                .orElseThrow(() -> new RolInvitacionInvalidoException(rolCodigo));

        LocalDateTime ahora = LocalDateTime.now();
        String tokenCrudo = generarTokenCrudo();

        InvitacionUsuario invitacion = new InvitacionUsuario();
        invitacion.setEmpresaId(empresaId);
        invitacion.setEmail(email);
        invitacion.setRolId(rol.getId());
        invitacion.setTokenHash(TokenHasher.sha256Hex(tokenCrudo));
        invitacion.setInvitadoPor(actorId);
        invitacion.setExpiraEn(ahora.plusDays(EXPIRACION_DIAS));
        invitacion.setEstado(ESTADO_PENDIENTE);
        invitacion.setCreateDate(ahora);
        invitacionUsuarioRepository.save(invitacion);

        usuarioRepository.findByEmail(email)
                .ifPresent(usuarioExistente -> crearMembresiaPendiente(usuarioExistente, empresaId, rol, actorId, ahora));

        return Optional.of(new InvitacionEmitida(email, tokenCrudo));
    }

    private void crearMembresiaPendiente(
            Usuario usuario, UUID empresaId, Rol rol, UUID invitadoPor, LocalDateTime ahora) {
        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuario.getId());
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rol.getId());
        membresia.setEstado(ESTADO_INVITACION_PENDIENTE);
        membresia.setInvitadoPor(invitadoPor);
        membresia.setFechaIngreso(ahora);
        usuarioEmpresaRepository.save(membresia);
    }

    private String generarTokenCrudo() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Aceptación de una invitación por un invitado que YA tiene cuenta ({@code usuario}): activa
     * la membresía {@code usuario_empresa} sembrada como {@code INVITACION_PENDIENTE} por
     * {@link #emitir}, marca la invitación {@code ACEPTADA} y aplica el hook de MFA cuando el
     * rol aceptado es {@code ADMIN_EMPRESA} (decisión D del design.md, corregida por la decisión
     * E: aquí SÍ aplica el token-continuation vía {@code SesionService.seleccionarTenant}, a
     * diferencia de {@code PATCH /usuarios/{id}/rol}).
     *
     * <p>{@code usuarioId} es el llamador autenticado (el propio invitado, resuelto por el
     * controlador vía {@code usuarioIdAutenticado()}) -- no se re-deriva del correo de la
     * invitación: la fila {@code usuario_empresa(INVITACION_PENDIENTE)} ya quedó anclada a un
     * {@code usuario_id} concreto en {@link #emitir}, y si el llamador autenticado no es ese
     * usuario, no existe una membresía pendiente que activar para él -- se rechaza con el mismo
     * {@link InvitacionInvalidaException} genérico, sin distinguir el motivo (anti-enumeración).
     *
     * <p>{@code noRollbackFor}: la escritura perezosa de {@code estado='EXPIRADA'} hecha por
     * {@link #resolverInvitacionValida} DEBE sobrevivir aunque el método termine lanzando esta
     * misma excepción -- sin esta anotación, el rollback por defecto de Spring ante cualquier
     * {@code RuntimeException} deshace ese UPDATE junto con todo lo demás, y la fila quedaría
     * {@code PENDIENTE} para siempre pese a estar vencida (decisión F de design.md: expiración
     * perezosa, sin job programado). Ningún otro write ocurre antes de esta excepción -- se
     * lanza siempre como primer paso del método, antes de tocar la membresía -- así que no hay
     * riesgo de dejar estado parcial de la aceptación en sí.
     */
    @Transactional(noRollbackFor = InvitacionInvalidaException.class)
    public AceptacionResultado aceptar(String tokenCrudo, UUID usuarioId) {
        InvitacionUsuario invitacion = resolverInvitacionValida(tokenCrudo);

        UsuarioEmpresa membresia = usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, invitacion.getEmpresaId(), ESTADO_INVITACION_PENDIENTE)
                .orElseThrow(InvitacionInvalidaException::new);

        membresia.setEstado(ESTADO_ACTIVO);
        usuarioEmpresaRepository.save(membresia);

        invitacion.setEstado(ESTADO_ACEPTADA);
        invitacionUsuarioRepository.save(invitacion);

        Rol rol = rolRepository.findById(invitacion.getRolId())
                .orElseThrow(() -> new IllegalStateException(
                        "invitacion_usuario referencia un rol_id inexistente: " + invitacion.getRolId()));

        if (ROL_ADMIN_EMPRESA.equals(rol.getCodigo())) {
            Usuario usuario = usuarioRepository.findById(usuarioId)
                    .orElseThrow(() -> new IllegalStateException(
                            "usuario_empresa referencia un usuario_id inexistente: " + usuarioId));
            usuario.setMfaRequerido(true);
            usuarioRepository.save(usuario);
        }

        return new AceptacionResultado(invitacion.getEmpresaId(), rol.getCodigo());
    }

    /**
     * Compartido con {@code RegistroService#registrarPorInvitacion} (Fase B, PR4 -- registro
     * por invitación de un invitado que NO tiene cuenta): valida el token con la MISMA lógica
     * de {@link #resolverInvitacionValida} usada por {@link #aceptar} y devuelve la fila viva,
     * SIN mutarla a {@code ACEPTADA} -- esa transición ocurre en {@code RegistroService}, recién
     * después de que el {@code usuario} y la {@code usuario_empresa} nuevos ya se guardaron, para
     * que las 3 escrituras (usuario + membresía + invitación) queden en la misma transacción y
     * puedan hacer rollback juntas si cualquiera falla.
     *
     * <p>{@code noRollbackFor}: mismo motivo exacto que {@link #aceptar} -- ver su javadoc --
     * la escritura perezosa de {@code estado='EXPIRADA'} debe sobrevivir aunque este método
     * termine lanzando {@link InvitacionInvalidaException}.
     */
    @Transactional(noRollbackFor = InvitacionInvalidaException.class)
    public InvitacionUsuario consumirParaRegistro(String tokenCrudo) {
        return resolverInvitacionValida(tokenCrudo);
    }

    /**
     * Valida el token contra las 4 causas de rechazo de design.md (inexistente, no
     * {@code PENDIENTE} -- ya {@code ACEPTADA} o {@code REVOCADA} --, o vencido) con un solo
     * mensaje ({@link InvitacionInvalidaException}, sin distinguir el motivo). La expiración es
     * perezosa (decisión F): se detecta en esta lectura y la fila se marca {@code EXPIRADA} antes
     * de fallar, en vez de depender de un job programado.
     */
    private InvitacionUsuario resolverInvitacionValida(String tokenCrudo) {
        InvitacionUsuario invitacion = invitacionUsuarioRepository.findByTokenHash(TokenHasher.sha256Hex(tokenCrudo))
                .orElseThrow(InvitacionInvalidaException::new);

        if (!ESTADO_PENDIENTE.equals(invitacion.getEstado())) {
            throw new InvitacionInvalidaException();
        }

        if (invitacion.getExpiraEn().isBefore(LocalDateTime.now())) {
            invitacion.setEstado(ESTADO_EXPIRADA);
            invitacionUsuarioRepository.save(invitacion);
            throw new InvitacionInvalidaException();
        }

        return invitacion;
    }

    /** {@code tokenCrudo} es de un solo uso por el llamador: enviarlo por correo y descartarlo. */
    public record InvitacionEmitida(String email, String tokenCrudo) {
    }

    /** Resultado de {@link #aceptar}: la empresa y el rol resueltos, para encadenar {@code SesionService.seleccionarTenant}. */
    public record AceptacionResultado(UUID empresaId, String rolCodigo) {
    }
}
