package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * No existe fila de {@code contador_consecutivo} para la combinación
 * {@code (empresaId, ambiente, tipoComprobante)} solicitada. Fuera de alcance de esta fase la
 * creación automática de la fila -- eso pertenece a la orquestación de alta de empresa/factura.
 */
public class ContadorConsecutivoNoEncontradoException extends RuntimeException {

    public ContadorConsecutivoNoEncontradoException(UUID empresaId, String ambiente, String tipoComprobante) {
        super("No existe contador_consecutivo para empresaId=%s, ambiente=%s, tipoComprobante=%s"
                .formatted(empresaId, ambiente, tipoComprobante));
    }
}
