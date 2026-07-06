package cr.ac.fractall.seguridad.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RefrescarTokenRequest(

        @NotBlank(message = "El refreshToken es obligatorio")
        String refreshToken,

        @NotNull(message = "El empresaId es obligatorio")
        UUID empresaId) {
}
