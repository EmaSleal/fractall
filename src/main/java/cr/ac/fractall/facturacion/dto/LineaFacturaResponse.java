package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
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
        BigDecimal montoExoneracionAplicado,
        String tipoTransaccion,
        String unidadMedidaComercial,
        BigDecimal ivaCobradoFabrica,
        BigDecimal factorCalculoIva,
        List<CodigoComercialResponse> codigosComerciales,
        List<DescuentoResponse> descuentos,
        ExoneracionResponse exoneracion) {

    public static LineaFacturaResponse desde(LineaFactura linea) {
        return desde(linea, List.of(), List.of(), null);
    }

    public static LineaFacturaResponse desde(
            LineaFactura linea,
            List<LineaCodigoComercial> codigos,
            List<LineaDescuento> descuentosEntidades,
            ImpuestoLineaExoneracion exoneracionEntidad) {
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
                linea.getMontoExoneracionAplicado(),
                linea.getTipoTransaccion(),
                linea.getUnidadMedidaComercial(),
                linea.getIvaCobradoFabrica(),
                linea.getFactorCalculoIva(),
                codigos.stream().map(CodigoComercialResponse::desde).toList(),
                descuentosEntidades.stream().map(DescuentoResponse::desde).toList(),
                exoneracionEntidad != null ? ExoneracionResponse.desde(exoneracionEntidad) : null);
    }
}
