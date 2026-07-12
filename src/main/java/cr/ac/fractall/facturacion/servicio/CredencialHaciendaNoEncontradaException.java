package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * No existe fila de {@code credencial_hacienda} para la combinación {@code (empresaId, ambiente)}
 * solicitada -- mismo motivo/estilo que {@code ContadorConsecutivoNoEncontradoException}. Sin una
 * credencial configurada, ni {@link ComprobanteHaciendaEnvioService#enviarComprobante} ni
 * {@link ComprobanteHaciendaEnvioService#consultarYActualizar} pueden identificar con qué
 * credencial de Hacienda operar.
 */
public class CredencialHaciendaNoEncontradaException extends RuntimeException {

    public CredencialHaciendaNoEncontradaException(UUID empresaId, String ambiente) {
        super("No existe credencial_hacienda para empresaId=%s, ambiente=%s".formatted(empresaId, ambiente));
    }
}
