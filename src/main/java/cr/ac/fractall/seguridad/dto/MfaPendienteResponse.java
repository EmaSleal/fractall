package cr.ac.fractall.seguridad.dto;

/**
 * Respuesta de {@code POST /auth/login} o {@code POST /auth/seleccionar-tenant} cuando la
 * membresía resuelta es {@code ADMIN_EMPRESA} (sección 3.3, MFA obligatorio): el token de
 * alcance mínimo "MFA pendiente" debe canjearse de inmediato contra
 * {@code POST /auth/mfa/enrolar} (si {@code requiereEnrolamiento = true}, primera vez) o
 * {@code POST /auth/mfa/verificar} (si {@code false}, usuario ya enrolado en un login previo).
 */
public record MfaPendienteResponse(String tokenMfaPendiente, boolean requiereEnrolamiento) {
}
