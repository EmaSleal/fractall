package cr.ac.fractall.catalogo.servicio;

import java.util.UUID;

/**
 * Ninguna {@code ClienteExoneracion} con este id existe para el tenant actual -- incluye tanto
 * "no existe" como "existe mas pertenece a otra empresa" (mismo motivo que
 * {@code ProductoNoEncontradoException}, ver su javadoc).
 */
public class ClienteExoneracionNoEncontradaException extends RuntimeException {

    public ClienteExoneracionNoEncontradaException(UUID id) {
        super("Exoneración no encontrada: " + id);
    }
}
