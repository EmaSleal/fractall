package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Registro de cobros (Release 3 / Fase C, ver diseño de {@code cobro_factura}, decisión F) solo
 * aplica a facturas con {@code condicion_venta IN ('02','03','04')} (crédito, consignación,
 * apartado). Contado ({@code '01'}) y arrendamiento ({@code '05'}/{@code '06'}) no tienen saldo
 * pendiente que cobrar en este sentido. Mapeada a HTTP 400 por {@code GlobalExceptionHandler} --
 * distinta de 404: la factura SÍ existe y pertenece al tenant actual, pero está fuera del alcance
 * de esta operación (no es una referencia inexistente ni un conflicto de estado de datos).
 */
public class FacturaNoCobrableException extends RuntimeException {

    public FacturaNoCobrableException(UUID facturaId, String condicionVenta) {
        super("La factura " + facturaId + " tiene condicion_venta " + condicionVenta
                + " -- el registro de cobros solo aplica a credito (02), consignacion (03) y apartado (04)");
    }
}
