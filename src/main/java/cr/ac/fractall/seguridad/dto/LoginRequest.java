package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @Schema(description = "User account email address", example = "empresa@correo.com")
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        String email,

        @Schema(description = "Account password", example = "S3cr3t!")
        @NotBlank(message = "La contraseña es obligatoria")
        String password) {
}
