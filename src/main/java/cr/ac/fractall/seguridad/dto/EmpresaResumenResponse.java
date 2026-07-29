package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Resumen de una empresa/membresía activa del usuario autenticado -- usado tanto en
 * {@code GET /auth/mis-empresas} como en {@code GET /auth/perfil}.
 */
@Schema(description = "Company summary as seen from a user's membership")
public record EmpresaResumenResponse(
        @Schema(description = "Unique identifier of the company") UUID empresaId,
        @Schema(description = "Legal company name (razón social)") String razonSocial,
        @Schema(description = "Trade name used commercially (may differ from razonSocial)") String nombreComercial,
        @Schema(description = "Role code assigned to the user within this company") String rolCodigo,
        @Schema(description = "Current status of the user's membership (e.g. ACTIVO, INACTIVO)") String estadoMembresia) {
}
