package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Regla de negocio 3 (Release 2 / Fase B, tope por línea, ver diseño D-E): la cantidad acreditada
 * en una línea de Nota de Crédito no puede exceder la cantidad de la línea de la factura origen --
 * una NC nunca reprisa, solo puede acreditar hasta lo que ya se facturó. Mapeada a HTTP 400 por
 * {@code GlobalExceptionHandler}.
 */
public class CantidadAcreditadaExcedeOrigenException extends RuntimeException {

    public CantidadAcreditadaExcedeOrigenException(
            UUID lineaOrigenId, BigDecimal cantidadSolicitada, BigDecimal cantidadOrigen) {
        super("La cantidad acreditada (" + cantidadSolicitada + ") para la línea " + lineaOrigenId
                + " excede la cantidad de la línea origen (" + cantidadOrigen + ")");
    }
}
