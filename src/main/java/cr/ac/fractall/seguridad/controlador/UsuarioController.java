package cr.ac.fractall.seguridad.controlador;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.notificaciones.servicio.EmailNotificacionService;
import cr.ac.fractall.seguridad.dto.AccessTokenResponse;
import cr.ac.fractall.seguridad.dto.InvitarUsuarioRequest;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.seguridad.dto.MfaPendienteResponse;
import cr.ac.fractall.seguridad.dto.MiembroResponse;
import cr.ac.fractall.seguridad.servicio.InvitacionUsuarioService;
import cr.ac.fractall.seguridad.servicio.MembresiaAdminService;
import cr.ac.fractall.seguridad.servicio.SesionResultado;
import cr.ac.fractall.seguridad.servicio.SesionService;
import cr.ac.fractall.seguridad.servicio.TokensAcceso;
import cr.ac.fractall.tenant.TenantContext;
import jakarta.validation.Valid;

/**
 * {@code /usuarios/*} (Fase B, invitación y administración de membresías -- ver design.md).
 * Cubierto por {@code .anyRequest().authenticated()} de {@code SecurityConfig} -- no requiere
 * cambios de seguridad adicionales.
 */
@Tag(name = "Usuarios", description = "Invitación y administración de miembros de la empresa")
@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private static final MensajeResponse MENSAJE_SIN_AUTENTICAR = new MensajeResponse("No autenticado.");

    // Anti-enumeración (design.md, sección "InvitacionUsuarioService"): mismo mensaje SIEMPRE,
    // exista o no una cuenta con ese correo, y aunque ya exista una invitación viva para él.
    private static final MensajeResponse MENSAJE_INVITACION_GENERICA = new MensajeResponse(
            "Si el correo es válido, se enviará una invitación en unos minutos.");

    private static final String ASUNTO_INVITACION = "Te invitaron a una empresa en Fractall";

    private final InvitacionUsuarioService invitacionUsuarioService;
    private final EmailNotificacionService emailNotificacionService;
    private final SesionService sesionService;
    private final MembresiaAdminService membresiaAdminService;

    public UsuarioController(
            InvitacionUsuarioService invitacionUsuarioService,
            EmailNotificacionService emailNotificacionService,
            SesionService sesionService,
            MembresiaAdminService membresiaAdminService) {
        this.invitacionUsuarioService = invitacionUsuarioService;
        this.emailNotificacionService = emailNotificacionService;
        this.sesionService = sesionService;
        this.membresiaAdminService = membresiaAdminService;
    }

    @Operation(summary = "Invitar a una persona a la empresa activa")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/invitar")
    public ResponseEntity<MensajeResponse> invitar(@Valid @RequestBody InvitarUsuarioRequest request) {
        Optional<UUID> actorId = usuarioIdAutenticado();
        if (actorId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        UUID empresaId = TenantContext.get();
        Optional<InvitacionUsuarioService.InvitacionEmitida> resultado = invitacionUsuarioService.emitir(
                actorId.get(), empresaId, request.email(), request.rolCodigo());

        // El envío ocurre DESPUÉS de que emitir() ya hizo commit (la llamada anterior ya
        // retornó): un fallo de Resend nunca debe revertir la invitación -- mismo criterio
        // que AuthController#registrar.
        resultado.ifPresent(r -> emailNotificacionService.enviarConReintento(
                r.email(), ASUNTO_INVITACION, construirHtmlInvitacion(r.tokenCrudo())));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MENSAJE_INVITACION_GENERICA);
    }

    /**
     * Aceptación de una invitación por un invitado que YA tiene cuenta (design.md, decisión D
     * corregida por la decisión E: el token-continuation SÍ aplica aquí, a diferencia de
     * {@code PATCH /usuarios/{id}/rol}). El único guard es el propio token -- ninguna llamada a
     * {@code PermisoGuard} -- pero el endpoint sigue exigiendo autenticación (ver la nota de
     * clase sobre {@code SecurityConfig}): el invitado debe estar logueado con SU tenant actual
     * (que por definición no es el de la empresa que invita, {@code JwtTenantFilter}) para que
     * {@code usuarioIdAutenticado()} resuelva su identidad.
     *
     * <p>{@code sesionService.seleccionarTenant} se invoca DESPUÉS de que
     * {@code invitacionUsuarioService.aceptar} ya hizo commit (el límite transaccional de
     * {@code aceptar} termina al retornar de esta llamada) -- necesario porque
     * {@code seleccionarTenant} reconfirma la membresía {@code ACTIVO} recién activada.
     */
    @Operation(summary = "Aceptar una invitación como invitado que ya tiene cuenta")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/invitacion/{token}/aceptar")
    public ResponseEntity<?> aceptar(@PathVariable("token") String token) {
        Optional<UUID> usuarioId = usuarioIdAutenticado();
        if (usuarioId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        InvitacionUsuarioService.AceptacionResultado resultado =
                invitacionUsuarioService.aceptar(token, usuarioId.get());

        SesionResultado sesion = sesionService.seleccionarTenant(usuarioId.get(), resultado.empresaId());
        if (sesion.requiereMfa()) {
            return ResponseEntity.ok(new MfaPendienteResponse(sesion.tokenMfaPendiente(), sesion.mfaRequiereEnrolamiento()));
        }
        return ResponseEntity.ok(aRespuesta(sesion.tokens()));
    }

    /**
     * Listado de miembros (activos y pendientes) de la empresa activa del caller (Fase B,
     * PR5a -- ver design.md, sección {@code MembresiaAdminService}). Guardado por
     * {@code usuario.ver} dentro del servicio, nunca solo por autenticación.
     */
    @Operation(summary = "Listar miembros de la empresa activa")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<?> listar() {
        Optional<UUID> actorId = usuarioIdAutenticado();
        if (actorId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        UUID empresaId = TenantContext.get();
        List<MiembroResponse> miembros = membresiaAdminService.listar(actorId.get(), empresaId);
        return ResponseEntity.ok(miembros);
    }

    private AccessTokenResponse aRespuesta(TokensAcceso tokens) {
        return new AccessTokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.empresaId());
    }

    /** Mismo patrón que {@code AuthController#usuarioIdAutenticado} -- ver su javadoc. */
    private Optional<UUID> usuarioIdAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || !(autenticacion.getPrincipal() instanceof UUID usuarioId)) {
            return Optional.empty();
        }
        return Optional.of(usuarioId);
    }

    private String construirHtmlInvitacion(String tokenCrudo) {
        return "<p>Te invitaron a unirte a una empresa en Fractall.</p>"
                + "<p>Token de invitación: " + tokenCrudo + "</p>"
                + "<p>Este enlace expira en 7 días.</p>";
    }
}
