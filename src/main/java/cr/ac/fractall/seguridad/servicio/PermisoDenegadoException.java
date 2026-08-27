package cr.ac.fractall.seguridad.servicio;

/**
 * Lanzada por {@link PermisoGuard#exigir} cuando la membresía del actor no está ACTIVO en la
 * empresa objetivo o cuando el permiso solicitado no aparece en {@code permisos_efectivos} para
 * esa membresía. Mapeada a HTTP 403 por {@code GlobalExceptionHandler} -- primer 403 global de
 * la aplicación (ver diseño, decisión B).
 *
 * <p>Mensaje deliberadamente fijo: nunca incluye el código de permiso faltante ni el motivo
 * exacto (membresía inactiva vs. permiso ausente), para no filtrar el modelo de autorización al
 * llamador.
 */
public class PermisoDenegadoException extends RuntimeException {

    public PermisoDenegadoException() {
        super("No tienes permiso para realizar esta acción.");
    }
}
