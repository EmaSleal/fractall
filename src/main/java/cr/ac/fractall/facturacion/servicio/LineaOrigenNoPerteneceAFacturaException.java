package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Regla de negocio 2 (Release 2 / Fase B, ver diseño D-E): cada {@code lineaFacturaOrigenId} de
 * una Nota de Crédito debe pertenecer a la factura de referencia indicada en el mismo request --
 * previene acreditar líneas de OTRA factura del mismo tenant. Mapeada a HTTP 400 por {@code
 * GlobalExceptionHandler}.
 */
public class LineaOrigenNoPerteneceAFacturaException extends RuntimeException {

    public LineaOrigenNoPerteneceAFacturaException(UUID lineaId, UUID facturaId) {
        super("La línea " + lineaId + " no pertenece a la factura de referencia " + facturaId);
    }
}
