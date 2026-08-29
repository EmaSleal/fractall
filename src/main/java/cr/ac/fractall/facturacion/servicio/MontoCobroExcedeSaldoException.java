package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tope de sobre-cobro neteado contra Notas de Crédito ACEPTADAS (Release 3 / Fase C, ver diseño
 * de {@code cobro_factura}, decisión D5/G). Pre-chequeo en Java del saldo neto disponible ANTES
 * de intentar el {@code INSERT} -- byte-for-byte el mismo criterio que
 * {@link MontoNotaCreditoExcedeOrigenException}: un estado de datos (saldo ya consumido por
 * cobros/NC previos) entra en conflicto con una operación por lo demás bien formada. El trigger
 * {@code trg_validar_tope_cobro_factura} (V23) queda como defensa en profundidad para la carrera
 * de concurrencia entre dos cobros que ambos pasan este pre-chequeo antes de que cualquiera haga
 * commit -- ver {@code GlobalExceptionHandler#manejarErrorSqlNoCategorizado}. Mapeada a HTTP 409.
 */
public class MontoCobroExcedeSaldoException extends RuntimeException {

    public MontoCobroExcedeSaldoException(
            UUID facturaId, BigDecimal cobrosPrevios, BigDecimal montoActual, BigDecimal saldoNeto) {
        super("El monto cobrado (" + cobrosPrevios + " previos + " + montoActual
                + " actual) excede el saldo neto de la factura " + facturaId + " (" + saldoNeto + ")");
    }
}
