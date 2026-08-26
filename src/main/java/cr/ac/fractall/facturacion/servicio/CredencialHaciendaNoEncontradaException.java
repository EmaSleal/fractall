package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

import cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException;

/**
 * No existe fila de {@code credencial_hacienda} para la combinación {@code (empresaId, ambiente)}
 * solicitada -- mismo motivo/estilo que {@code ContadorConsecutivoNoEncontradoException}. Sin una
 * credencial configurada, ni {@link ComprobanteHaciendaEnvioService#enviarComprobante} ni
 * {@link ComprobanteHaciendaEnvioService#consultarYActualizar} pueden identificar con qué
 * credencial de Hacienda operar.
 *
 * <p>Extiende {@link HaciendaConfiguracionException} porque una credencial ausente es, por
 * definición, una falla de configuración: ningún reintento automático puede resolverla sin
 * intervención humana -- ver el javadoc de la superclase.
 */
public class CredencialHaciendaNoEncontradaException extends HaciendaConfiguracionException {

    public CredencialHaciendaNoEncontradaException(UUID empresaId, String ambiente) {
        super("No existe credencial_hacienda para empresaId=%s, ambiente=%s".formatted(empresaId, ambiente));
    }
}
