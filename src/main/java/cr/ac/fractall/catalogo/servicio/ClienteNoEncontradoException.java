package cr.ac.fractall.catalogo.servicio;

import java.util.UUID;

/**
 * Ningún {@code Cliente} con este id existe para el tenant actual -- incluye tanto "no existe"
 * como "existe mas pertenece a otra empresa" (mismo motivo que
 * {@code ProductoNoEncontradoException}, ver su javadoc).
 */
public class ClienteNoEncontradoException extends RuntimeException {

    public ClienteNoEncontradoException(UUID id) {
        super("Cliente no encontrado: " + id);
    }
}
