package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Ningún {@code ComprobanteElectronico} con este id existe para el tenant actual -- incluye
 * tanto "no existe" como "existe mas pertenece a otra empresa" (mismo motivo que
 * {@code ClienteNoEncontradoException}, ver su javadoc).
 */
public class ComprobanteElectronicoNoEncontradoException extends RuntimeException {

    public ComprobanteElectronicoNoEncontradoException(UUID id) {
        super("Comprobante electrónico no encontrado: " + id);
    }
}
