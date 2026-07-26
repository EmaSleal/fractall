package cr.ac.fractall.seguridad.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de {@code POST /auth/recuperar-password} (anti-enumeración: el endpoint SIEMPRE
 * responde con el mismo mensaje genérico, sin revelar si el email existe).
 */
public record RecuperarPasswordRequest(

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        String email) {
}
