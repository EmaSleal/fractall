package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Campos mínimos de {@code POST /auth/registro} (sección 3.1). Se limita estrictamente a
 * las columnas {@code NOT NULL} de {@code usuario} y {@code empresa} sin default de base de
 * datos utilizable desde Java ({@code nombre}, {@code email}, {@code password_hash},
 * {@code razon_social}) -- el resto del perfil fiscal de la empresa (identificación,
 * actividad, ubicación, certificado) se completa en un paso posterior, ya cubierto por la
 * máquina de estados de {@code empresa.status} (Fase 5, ver {@code fn_actualizar_status_empresa}).
 */
public record RegistroRequest(

        @Schema(description = "Full name of the account owner", example = "María García", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255)
        String nombre,

        @Schema(description = "Login email address", example = "empresa@correo.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email,

        @Schema(description = "Password (8–72 characters)", example = "S3cr3t!abc", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password,

        @Schema(description = "Legal company name (razón social)", example = "Mi Empresa S.A.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "La razón social es obligatoria")
        @Size(max = 255)
        String razonSocial,

        @Schema(description = "Whether this user requires MFA to log in. Defaults to true when omitted.", example = "true")
        Boolean activarMfa) {

    public RegistroRequest(String nombre, String email, String password, String razonSocial) {
        this(nombre, email, password, razonSocial, null);
    }
}
