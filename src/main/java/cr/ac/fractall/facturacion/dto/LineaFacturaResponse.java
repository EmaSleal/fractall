package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.LineaFactura;

public record LineaFacturaResponse(
        UUID id,
        UUID productoId,
        int numeroLinea,
        BigDecimal cantidad,
        BigDecimal precioUnitario,
        BigDecimal subtotal,
        String codigoCabysAplicado,
        boolean gravadoAplicado,
        BigDecimal porcentajeImpuestoAplicado,
        UUID exoneracionId,
        BigDecimal porcentajeExoneracionAplicado,
        BigDecimal montoExoneracionAplicado) {

    public static LineaFacturaResponse desde(LineaFactura linea) {
        return new LineaFacturaResponse(
                linea.getId(),
                linea.getProductoId(),
                linea.getNumeroLinea(),
                linea.getCantidad(),
                linea.getPrecioUnitario(),
                linea.getSubtotal(),
                linea.getCodigoCabysAplicado(),
                linea.isGravadoAplicado(),
                linea.getPorcentajeImpuestoAplicado(),
                linea.getExoneracionId(),
                linea.getPorcentajeExoneracionAplicado(),
                linea.getMontoExoneracionAplicado());
    }
}
