package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;

public record FacturaResponse(
        UUID id,
        UUID clienteId,
        BigDecimal subtotal,
        BigDecimal totalImpuesto,
        BigDecimal total,
        List<LineaFacturaResponse> lineas,
        UUID comprobanteId,
        String ambienteHacienda,
        String tipoComprobante,
        String consecutivo,
        String claveNumerica,
        String estado,
        LocalDateTime fechaEmision) {

    public static FacturaResponse desde(
            Factura factura, ComprobanteElectronico comprobante, List<LineaFacturaResponse> lineas) {
        return new FacturaResponse(
                factura.getId(),
                factura.getClienteId(),
                factura.getSubtotal(),
                factura.getTotalImpuesto(),
                factura.getTotal(),
                lineas,
                comprobante.getId(),
                comprobante.getAmbienteHacienda(),
                comprobante.getTipoComprobante(),
                comprobante.getConsecutivo(),
                comprobante.getClaveNumerica(),
                comprobante.getEstado(),
                comprobante.getFechaEmision());
    }
}
