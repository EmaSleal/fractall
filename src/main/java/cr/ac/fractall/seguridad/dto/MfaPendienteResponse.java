package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta de {@code POST /auth/login} o {@code POST /auth/seleccionar-tenant} cuando la
 * membresía resuelta es {@code ADMIN_EMPRESA} (sección 3.3, MFA obligatorio): el token de
 * alcance mínimo "MFA pendiente" debe canjearse de inmediato contra
 * {@code POST /auth/mfa/enrolar} (si {@code requiereEnrolamiento = true}, primera vez) o
 * {@code POST /auth/mfa/verificar} (si {@code false}, usuario ya enrolado en un login previo).
 */
@Schema(description = "Intermediate response when MFA verification is required before issuing an access token")
public record MfaPendienteResponse(
        @Schema(description = "Minimal-scope JWT (10 min) to exchange against /mfa/enrolar or /mfa/verificar") String tokenMfaPendiente,
        @Schema(description = "true = user has never enrolled TOTP, must call /mfa/enrolar first; false = already enrolled, must call /mfa/verificar") boolean requiereEnrolamiento) {
}
