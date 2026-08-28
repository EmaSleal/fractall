package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /auth/registro/invitacion} (Fase B, PR4 -- ver design.md, sección
 * "RegistroService.registrarPorInvitacion"). Deliberadamente SIN campo {@code email}: la
 * dirección de correo del nuevo {@code usuario} se toma siempre de
 * {@code invitacion.getEmail()}, nunca de la solicitud -- de lo contrario un token válido
 * podría canjearse hacia una cuenta con un correo distinto al que realmente recibió la
 * invitación.
 */
public record RegistroPorInvitacionRequest(

        @Schema(description = "Raw invitation token received by email", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El token de invitación es obligatorio")
        String invitacionToken,

        @Schema(description = "Full name of the new account owner", example = "María García", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255)
        String nombre,

        @Schema(description = "Password (8–72 characters)", example = "S3cr3t!abc", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password,

        @Schema(description = "Whether this user requires MFA to log in. Defaults to true when omitted.", example = "true")
        Boolean activarMfa) {
}
