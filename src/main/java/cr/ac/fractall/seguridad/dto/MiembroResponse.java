package cr.ac.fractall.seguridad.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Fila de {@code GET /usuarios} (Fase B, PR5a -- ver design.md, sección {@code MembresiaAdminService}).
 * Incluye membresías en cualquier estado ({@code ACTIVO}, {@code INVITACION_PENDIENTE},
 * {@code SUSPENDIDO}), no solo activas -- ver el requerimiento "Membership Listing" de spec.md.
 */
@Schema(description = "A member (or pending invitee) of the caller's current company")
public record MiembroResponse(
        @Schema(description = "Unique identifier of the member's user account") UUID usuarioId,
        @Schema(description = "Full display name of the member") String nombre,
        @Schema(description = "Email address of the member") String email,
        @Schema(description = "Role code assigned to this membership", example = "CONSULTA") String rolCodigo,
        @Schema(description = "Membership state", example = "ACTIVO") String estado,
        @Schema(description = "Date the membership was created") LocalDateTime fechaIngreso) {
}
