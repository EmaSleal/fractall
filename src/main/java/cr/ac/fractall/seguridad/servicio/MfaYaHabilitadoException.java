package cr.ac.fractall.seguridad.servicio;

/**
 * {@code usuario.mfa_habilitado = true} ya -- usada por {@code MfaService#enrolar} cuando se
 * intenta re-enrolar un usuario que ya completó el enrolamiento MFA (sección 3.3). No hay
 * flujo de "reemplazar/resetear" en este batch (fuera de alcance, ver
 * {@code plan-fases-release-1.md}).
 */
public class MfaYaHabilitadoException extends RuntimeException {
}
