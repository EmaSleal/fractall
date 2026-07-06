package cr.ac.fractall.shared;

/**
 * Señala que una operación de persistencia se intentó ejecutar sin un tenant
 * (empresa) resuelto en el contexto de ejecución actual.
 *
 * <p>El mecanismo de aislamiento multi-tenant falla de forma cerrada: ante la
 * ausencia de {@code empresa_id} en contexto, se bloquea la operación en lugar
 * de asumir un valor por defecto que podría filtrar datos entre empresas.
 *
 * <p>Ver sección 5.3 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
public class TenantNoResueltoException extends RuntimeException {

    public TenantNoResueltoException(String message) {
        super(message);
    }
}
