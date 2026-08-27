package cr.ac.fractall.seguridad.controlador;

import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.notificaciones.servicio.EmailNotificacionService;
import cr.ac.fractall.seguridad.dto.InvitarUsuarioRequest;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.seguridad.servicio.InvitacionUsuarioService;
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

    public UsuarioController(
            InvitacionUsuarioService invitacionUsuarioService,
            EmailNotificacionService emailNotificacionService) {
        this.invitacionUsuarioService = invitacionUsuarioService;
        this.emailNotificacionService = emailNotificacionService;
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
