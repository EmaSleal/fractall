package cr.ac.fractall.seguridad.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Cuerpo compartido por {@code POST /auth/mfa/confirmar} y {@code POST /auth/mfa/verificar}. */
public record MfaCodigoRequest(

        @NotBlank(message = "El código es obligatorio")
        @Pattern(regexp = "\\d{6}", message = "El código debe tener exactamente 6 dígitos")
        String codigo) {
}
