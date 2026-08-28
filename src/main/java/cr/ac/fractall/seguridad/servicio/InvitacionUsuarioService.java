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
    private static final String ESTADO_INVITACION_PENDIENTE = "INVITACION_PENDIENTE";
    private static final String PERMISO_INVITAR = "usuario.invitar";

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

    /** {@code tokenCrudo} es de un solo uso por el llamador: enviarlo por correo y descartarlo. */
    public record InvitacionEmitida(String email, String tokenCrudo) {
    }
}
