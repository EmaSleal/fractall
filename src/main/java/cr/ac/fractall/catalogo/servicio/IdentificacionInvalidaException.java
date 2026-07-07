package cr.ac.fractall.catalogo.servicio;

/**
 * El tipo de identificación no es uno de los 4 códigos conocidos (sección 4.11 de
 * {@code arquitectura-facturacion-electronica-cr.md}), o el número no cumple la longitud
 * esperada para el tipo declarado ({@code TipoIdentificacion#validarNumero}). Rechazo explícito
 * ANTES de {@code saveAndFlush} -- nada se persiste cuando se lanza.
 */
public class IdentificacionInvalidaException extends RuntimeException {

    public IdentificacionInvalidaException(String mensaje) {
        super(mensaje);
    }
}
