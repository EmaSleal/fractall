package cr.ac.fractall.catalogo.servicio;

/**
 * La llamada a la API de Hacienda para validar un código CABYS falló (error de red, 5xx,
 * timeout) -- se dispara cuando {@code CabysBusquedaDTO#getExitosa()} llega {@code false} o
 * {@code null}, es decir, la búsqueda misma no se pudo completar. Distinta de
 * {@link CodigoCabysInvalidoException} A PROPÓSITO: esa otra excepción solo debe lanzarse cuando
 * la búsqueda SÍ fue exitosa pero ningún resultado coincide exactamente con el código enviado --
 * conflacionar ambos casos le diría incorrectamente al cliente que su código válido está mal
 * cuando el problema real es una caída de la dependencia externa. Se mapea a 503 en
 * {@code ProductoController} (no 400: la solicitud del cliente no fue el problema).
 */
public class HaciendaNoDisponibleException extends RuntimeException {

    public HaciendaNoDisponibleException(String mensajeError) {
        super("No fue posible validar el código CABYS porque la API de Hacienda no está disponible"
                + (mensajeError != null && !mensajeError.isBlank() ? ": " + mensajeError : ""));
    }
}
