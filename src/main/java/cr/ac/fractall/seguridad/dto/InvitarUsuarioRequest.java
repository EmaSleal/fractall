package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /usuarios/invitar} (Fase B, invitación y administración de
 * membresías -- ver design.md). Sin campo de empresa: la empresa que invita es siempre la
 * del token del actor ({@code TenantContext}), nunca un valor de la solicitud (evita IDOR).
 */
public record InvitarUsuarioRequest(

        @Schema(description = "Email of the person being invited", example = "nuevo@empresa.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email,

        @Schema(description = "Role code to assign once the invitation is accepted", example = "CONSULTA", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El código de rol es obligatorio")
        String rolCodigo) {
}
