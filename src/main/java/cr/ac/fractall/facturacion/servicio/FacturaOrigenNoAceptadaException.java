package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Regla de negocio 1 (Release 2 / Fase B, ver diseño D-E): la factura de referencia de una Nota
 * de Crédito/Débito debe estar en estado {@code ACEPTADO} -- leído de {@code
 * comprobante_electronico.estado}, NO de {@code factura} (el ciclo de vida de Hacienda vive en el
 * comprobante, no en la factura misma; ver V4/V12). Mapeada a HTTP 409 por {@code
 * GlobalExceptionHandler} -- el estado actual del origen entra en conflicto con la operación
 * solicitada, mismo tratamiento que {@link ComprobanteNoReenviableException}.
 *
 * <p>Package: {@code facturacion.servicio} — mismo patrón de pares servicio + excepción ya
 * establecido en este paquete (no existe un paquete {@code excepcion} en este codebase).
 */
public class FacturaOrigenNoAceptadaException extends RuntimeException {

    public FacturaOrigenNoAceptadaException(UUID facturaId, String estado) {
        super("La factura de referencia " + facturaId
                + " no está en estado ACEPTADO (estado actual: " + estado + ")");
    }
}
