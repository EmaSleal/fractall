package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;

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
        LocalDateTime fechaEmision,
        String condicionVentaOtros,
        String codigoActividadReceptor,
        BigDecimal totalIvaDevuelto,
        List<OtrosCargoResponse> otrosCargos,
        List<ReferenciaResponse> informacionReferencia,
        List<MedioPagoResponse> mediosPago) {

    public static FacturaResponse desde(
            Factura factura, ComprobanteElectronico comprobante, List<LineaFacturaResponse> lineas) {
        return desde(factura, comprobante, lineas, List.of(), List.of(), List.of());
    }

    public static FacturaResponse desde(
            Factura factura,
            ComprobanteElectronico comprobante,
            List<LineaFacturaResponse> lineas,
            List<FacturaOtrosCargos> otrosCargosEntidades,
            List<FacturaInformacionReferencia> referenciasEntidades,
            List<FacturaMedioPago> mediosPagoEntidades) {
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
                comprobante.getFechaEmision(),
                factura.getCondicionVentaOtros(),
                factura.getCodigoActividadReceptor(),
                factura.getTotalIvaDevuelto(),
                otrosCargosEntidades.stream().map(OtrosCargoResponse::desde).toList(),
                referenciasEntidades.stream().map(ReferenciaResponse::desde).toList(),
                mediosPagoEntidades.stream().map(MedioPagoResponse::desde).toList());
    }
}
