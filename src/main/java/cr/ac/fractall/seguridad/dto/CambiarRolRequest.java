package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de {@code PATCH /usuarios/{usuarioId}/rol} (Fase B, PR5b -- ver design.md, sección
 * "UsuarioController"). Sin campo de empresa: la empresa objetivo es siempre la del token del
 * actor ({@code TenantContext}), nunca un valor de la solicitud (evita IDOR).
 */
public record CambiarRolRequest(

        @Schema(description = "New role code to assign to the member", example = "ADMIN_EMPRESA", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El código de rol es obligatorio")
        String rolCodigo) {
}
