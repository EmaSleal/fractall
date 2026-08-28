package cr.ac.fractall.seguridad.servicio;

/**
 * Un actor intenta aplicar una acción de administración de membresías (cambio de rol,
 * suspensión) sobre SU PROPIA membresía (Fase B, PR5b -- ver design.md, sección
 * "MembresiaAdminService"). Mapeada a HTTP 409 por {@code GlobalExceptionHandler} (decisión G):
 * es un conflicto de estado sobre una operación por lo demás autorizada y bien formada -- el
 * actor SÍ tiene el permiso, pero la regla de negocio prohíbe dirigirlo contra sí mismo -- nunca
 * un 400 (entrada malformada) ni un 403 (falta de autorización).
 */
public class AutoGestionNoPermitidaException extends RuntimeException {

    public AutoGestionNoPermitidaException() {
        super("No puedes aplicar esta acción sobre tu propia membresía.");
    }
}
