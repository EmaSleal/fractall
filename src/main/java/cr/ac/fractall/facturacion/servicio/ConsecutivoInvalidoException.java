package cr.ac.fractall.facturacion.servicio;

/**
 * El {@code nuevoValor} solicitado para {@code fijarValor} no es estrictamente mayor al
 * {@code valorActual} de {@code contador_consecutivo} -- regla de negocio: el consecutivo solo
 * puede subir, nunca bajar ni igualar el actual (evita reproducir el rechazo real de Hacienda por
 * reusar un consecutivo ya emitido tras una desincronización del contador).
 */
public class ConsecutivoInvalidoException extends RuntimeException {

    public ConsecutivoInvalidoException(long valorActual, long nuevoValor) {
        super("El nuevo valor debe ser mayor al consecutivo actual (%d)".formatted(valorActual));
    }
}
