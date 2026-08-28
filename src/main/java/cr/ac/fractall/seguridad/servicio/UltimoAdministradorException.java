package cr.ac.fractall.seguridad.servicio;

/**
 * Se intenta degradar o suspender la única membresía {@code ADMIN_EMPRESA} ACTIVA que le queda a
 * la empresa (Fase B, PR5b -- ver design.md, sección "MembresiaAdminService"). Mapeada a HTTP 409
 * por {@code GlobalExceptionHandler} (decisión G): la empresa se quedaría sin ningún
 * administrador, un conflicto de estado del recurso, no un dato de entrada inválido.
 */
public class UltimoAdministradorException extends RuntimeException {

    public UltimoAdministradorException() {
        super("La empresa debe conservar al menos un administrador activo.");
    }
}
