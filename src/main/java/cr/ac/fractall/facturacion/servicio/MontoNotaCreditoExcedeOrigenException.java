package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Regla de negocio 3 (Release 2 / Fase B, tope de monto acumulado, ver diseño D-E y sus "Open
 * Questions"): pre-chequeo en Java del saldo disponible de la factura origen ANTES de intentar el
 * {@code INSERT} -- el trigger {@code trg_validar_tope_nota_credito} (V18) es defensa en
 * profundidad, reservada para la carrera de concurrencia entre dos Notas de Crédito que ambas
 * pasan este pre-chequeo antes de que cualquiera haga commit (ver {@code GlobalExceptionHandler
 * #manejarErrorSqlNoCategorizado} para ese camino). El nombre de esta excepción no estaba fijado
 * en la tabla de excepciones→status del diseño (D-G solo listaba 4 -- gap documentado); se sigue
 * el mismo patrón de nombrado que {@link CantidadAcreditadaExcedeOrigenException}. Mapeada a HTTP
 * 409 por {@code GlobalExceptionHandler} -- mismo tratamiento que {@link
 * FacturaOrigenNoAceptadaException}: un estado de datos (saldo ya consumido por NC previas) entra
 * en conflicto con la operación solicitada.
 */
public class MontoNotaCreditoExcedeOrigenException extends RuntimeException {

    public MontoNotaCreditoExcedeOrigenException(
            UUID facturaOrigenId, BigDecimal sumaNcPrevias, BigDecimal totalNcActual, BigDecimal totalOrigen) {
        super("El monto de las Notas de Crédito (" + sumaNcPrevias + " previas + " + totalNcActual
                + " actual) excede el total de la factura origen " + facturaOrigenId + " (" + totalOrigen + ")");
    }
}
