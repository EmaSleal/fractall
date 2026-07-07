package cr.ac.fractall.catalogo.servicio;

/**
 * Ya existe un {@code ClienteExoneracion} con este {@code numeroDocumento} para el mismo
 * {@code clienteId} -- {@code UNIQUE(cliente_id, numero_documento)}
 * ({@code V8__cliente_exoneracion.sql}). Mismo principio que
 * {@code ClienteDuplicadoException}/{@code ProductoDuplicadoException}: se rechaza (409) con un
 * mensaje de dominio ANTES de {@code saveAndFlush}, nunca como una
 * {@code DataIntegrityViolationException} sin capturar.
 */
public class ClienteExoneracionDuplicadaException extends RuntimeException {

    public ClienteExoneracionDuplicadaException(String numeroDocumento) {
        super("Ya existe una exoneración con el número de documento '" + numeroDocumento + "' para este cliente");
    }
}
