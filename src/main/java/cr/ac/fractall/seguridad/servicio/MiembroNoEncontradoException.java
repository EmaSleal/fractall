package cr.ac.fractall.seguridad.servicio;

/**
 * El {@code usuarioId} objetivo de una operación de administración de membresías no tiene
 * ninguna fila {@code usuario_empresa} en la empresa ACTUAL del actor (Fase B, PR5b -- ver
 * design.md, sección "MembresiaAdminService"). Búsqueda explícitamente acotada por tenant: un
 * usuario que sí existe pero es miembro de otra empresa también dispara esta excepción, nunca una
 * fuga cross-tenant de 200/500 (mismo criterio que {@code AislamientoMultiTenantTest}). Mapeada a
 * HTTP 404 por {@code GlobalExceptionHandler}.
 */
public class MiembroNoEncontradoException extends RuntimeException {

    public MiembroNoEncontradoException() {
        super("El usuario indicado no es miembro de esta empresa.");
    }
}
