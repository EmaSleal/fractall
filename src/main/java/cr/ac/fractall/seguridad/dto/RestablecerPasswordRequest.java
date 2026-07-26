package cr.ac.fractall.seguridad.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /auth/restablecer-password}. Las mismas reglas de validación que
 * {@code RegistroRequest.password}: min=8, max=72 (límite de BCrypt).
 */
public record RestablecerPasswordRequest(

        @NotBlank(message = "El token es obligatorio")
        String token,

        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String nuevaPassword) {
}
