package cr.ac.fractall.facturacion.servicio;

/**
 * {@code condicionVenta = '02'} (crédito) sin {@code plazoCredito} -- mismo requisito que ya
 * exige el {@code CHECK} de {@code factura} en {@code V4__catalogo_y_facturacion.sql}, validado
 * aquí en Java ANTES de persistir para no depender de ese {@code CHECK} para dar un mensaje
 * claro (ver {@code FacturaService#validarCondicionVenta}).
 */
public class CondicionVentaInvalidaException extends RuntimeException {

    public CondicionVentaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
