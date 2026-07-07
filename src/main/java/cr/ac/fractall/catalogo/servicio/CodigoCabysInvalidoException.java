package cr.ac.fractall.catalogo.servicio;

/**
 * El código CABYS enviado no tuvo una coincidencia EXACTA (campo {@code codigo}) entre los
 * resultados devueltos por {@code HaciendaApiService#buscarCabys} -- sin coincidencia exacta no
 * hay fallback ni coincidencia parcial (sección 4.10 de
 * {@code arquitectura-facturacion-electronica-cr.md}). Nada se persiste cuando se lanza.
 */
public class CodigoCabysInvalidoException extends RuntimeException {

    public CodigoCabysInvalidoException(String codigoCabys) {
        super("El código CABYS '" + codigoCabys + "' no tiene una coincidencia exacta en la API de Hacienda");
    }
}
