package cr.ac.fractall.seguridad.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de {@code POST /auth/logout}. {@code todasLasSesiones} controla si solo se revoca
 * el refresh token proporcionado ({@code false}) o todos los tokens activos del usuario
 * ({@code true}).
 */
public record LogoutRequest(

        @NotBlank(message = "El refreshToken es obligatorio")
        String refreshToken,

        boolean todasLasSesiones) {
}
