package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta de {@code POST /auth/login} cuando el usuario tiene 2+ empresas activas
 * (sección 3.2, punto 2): el token de alcance mínimo debe canjearse de inmediato contra
 * {@code POST /auth/seleccionar-tenant}.
 */
@Schema(description = "Response when the user belongs to multiple companies and must select one before proceeding")
public record SeleccionTenantRequeridaResponse(
        @Schema(description = "Minimal-scope JWT (5 min) to exchange against /auth/seleccionar-tenant") String tokenSeleccionTenant) {
}
