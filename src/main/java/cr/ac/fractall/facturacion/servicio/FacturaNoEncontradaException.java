package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Lanzada por {@link FacturaService#obtener} cuando el id solicitado no existe o pertenece a otro
 * tenant (el filtro {@code @TenantId} hace que {@code findById} devuelva vacío en ambos casos).
 * Mapeada a HTTP 404 por {@code GlobalExceptionHandler}. Req: FR-2.
 *
 * <p>Package: {@code facturacion.servicio} — mismo patrón de pares servicio + excepción ya
 * establecido por {@code ClienteNoEncontradoException}, {@code ProductoNoEncontradoException} y
 * {@code ClienteExoneracionNoEncontradaException}, que también viven en su paquete servicio
 * respectivo (no existe un paquete {@code excepcion} en este codebase).
 */
public class FacturaNoEncontradaException extends RuntimeException {

    public FacturaNoEncontradaException(UUID id) {
        super("Factura no encontrada: " + id);
    }
}
