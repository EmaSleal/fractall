package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta de {@code GET /auth/perfil}: datos del usuario autenticado, empresa activa y
 * permisos efectivos en esa empresa (sección 3.5 de la especificación).
 */
@Schema(description = "Authenticated user profile")
public record PerfilResponse(
        @Schema(description = "Unique identifier of the authenticated user") UUID usuarioId,
        @Schema(description = "Full display name of the user") String nombre,
        @Schema(description = "Email address of the user") String email,
        @Schema(description = "Whether TOTP MFA is currently enabled for this user") boolean mfaHabilitado,
        @Schema(description = "Summary of the company currently active in this session") EmpresaResumenResponse empresaActiva,
        @Schema(description = "List of permission codes granted to the user within the active company") List<String> permisos) {
}
