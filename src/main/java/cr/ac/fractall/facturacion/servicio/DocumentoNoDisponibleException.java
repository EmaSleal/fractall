package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Lanzada cuando el {@link cr.ac.fractall.facturacion.modelo.ComprobanteElectronico} existe para
 * la factura solicitada pero la columna de referencia OCI del documento pedido es {@code null}
 * (e.g. estado anterior a ENVIADO, o ventana de fallo parcial en la subida del XML firmado).
 *
 * <p>Se mapea a HTTP 404 (no 409/500) vía {@link cr.ac.fractall.shared.GlobalExceptionHandler}:
 * el cliente debe tratarlo como "recurso aún no disponible", no como un error de servidor.
 *
 * <p>Package: {@code facturacion.servicio} -- mismo patrón que
 * {@link FacturaNoEncontradaException}: no existe un paquete {@code excepcion} en este codebase.
 */
public class DocumentoNoDisponibleException extends RuntimeException {

    public DocumentoNoDisponibleException(UUID facturaId, String documento) {
        super("El documento '" + documento + "' aún no está disponible para la factura " + facturaId);
    }
}
