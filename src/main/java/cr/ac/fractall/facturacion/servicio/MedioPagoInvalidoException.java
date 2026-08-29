package cr.ac.fractall.facturacion.servicio;

/**
 * {@code medio_pago} de {@code RegistrarCobroRequest} no corresponde a ningún código del catálogo
 * FE v4.4 (Release 3 / Fase C, ver diseño de {@code cobro_factura}, decisión H). Se relanza aquí
 * a partir de la {@link IllegalArgumentException} que lanza {@code TipoMedioPago#fromCodigo}
 * porque {@code GlobalExceptionHandler} no tiene manejador para {@code IllegalArgumentException}
 * -- sin este relanzamiento, propagarla directamente escalaría como un 500 crudo. Mapeada a HTTP
 * 400: es un dato de entrada del llamador inválido, no un id de recurso ni un conflicto de estado.
 */
public class MedioPagoInvalidoException extends RuntimeException {

    public MedioPagoInvalidoException(String codigo) {
        super("Código de medio de pago inválido: " + codigo);
    }
}
