package cr.ac.fractall.hacienda.servicio;

/**
 * La llamada a la API de Hacienda para obtener el tipo de cambio del dólar falló (error de red,
 * 5xx, timeout, respuesta vacía) -- a diferencia de {@code buscarCabysPorCodigo}, acá no hay
 * noción de "no encontrado" como resultado válido de negocio (Hacienda siempre publica un valor
 * para el día), así que un fallo de la llamada se modela como excepción en vez de un DTO con
 * {@code exitosa=false}.
 *
 * <p>Vive en {@code hacienda.servicio} (a diferencia de
 * {@code cr.ac.fractall.catalogo.servicio.HaciendaNoDisponibleException}, que vive en
 * {@code catalogo.servicio}): esa otra excepción la LANZA {@code ProductoService} (el consumidor),
 * no {@code HaciendaConsultaServiceImpl}, que solo devuelve un DTO con {@code exitosa=false} --
 * "vive cerca de quien la lanza" para esa excepción coincide con "vive cerca del consumidor".
 * Acá {@code HaciendaConsultaServiceImpl#consultarTipoCambioDolar} lanza esta excepción
 * directamente, así que si viviera en {@code facturacion.servicio} (su consumidor,
 * {@code FacturaService}) se formaría una dependencia circular de paquetes: {@code facturacion}
 * ya depende de {@code hacienda} (vía {@code HaciendaApiService}), y {@code hacienda} pasaría a
 * depender de {@code facturacion} solo por esta excepción. Se mapea a 503 en
 * {@code GlobalExceptionHandler}.
 */
public class TipoCambioNoDisponibleException extends RuntimeException {

    public TipoCambioNoDisponibleException(String mensajeError) {
        super("No fue posible obtener el tipo de cambio del dólar porque la API de Hacienda no está disponible"
                + (mensajeError != null && !mensajeError.isBlank() ? ": " + mensajeError : ""));
    }
}
