package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        String clienteNombre,
        BigDecimal subtotal,
        BigDecimal totalImpuesto,
        BigDecimal total,
        List<LineaFacturaResponse> lineas,
        UUID comprobanteId,
        String ambienteHacienda,
        String tipoComprobante,
        String consecutivo,
        String claveNumerica,
        UUID facturaReferenciaId,
        String estado,
        Instant fechaEmision,
        String condicionVentaOtros,
        String codigoActividadReceptor,
        BigDecimal totalIvaDevuelto,
        List<OtrosCargoResponse> otrosCargos,
        List<ReferenciaResponse> informacionReferencia,
        List<MedioPagoResponse> mediosPago,
        String codigoRespuesta,
        String mensajeRespuesta,
        Instant fechaRespuesta,
        String ultimoResultadoConsulta,
        Integer intentosEnvio) {

    public static FacturaResponse desde(
            Factura factura, ComprobanteElectronico comprobante, List<LineaFacturaResponse> lineas) {
        return desde(factura, comprobante, lineas, List.of(), List.of(), List.of(), null);
    }

    public static FacturaResponse desde(
            Factura factura,
            ComprobanteElectronico comprobante,
            List<LineaFacturaResponse> lineas,
            List<FacturaOtrosCargos> otrosCargosEntidades,
            List<FacturaInformacionReferencia> referenciasEntidades,
            List<FacturaMedioPago> mediosPagoEntidades,
            String clienteNombre) {
        return new FacturaResponse(
                factura.getId(),
                factura.getClienteId(),
                clienteNombre,
                factura.getSubtotal(),
                factura.getTotalImpuesto(),
                factura.getTotal(),
                lineas,
                comprobante.getId(),
                comprobante.getAmbienteHacienda(),
                comprobante.getTipoComprobante(),
                comprobante.getConsecutivo(),
                comprobante.getClaveNumerica(),
                factura.getFacturaReferenciaId(),
                comprobante.getEstado(),
                aInstanteUtc(comprobante.getFechaEmision()),
                factura.getCondicionVentaOtros(),
                factura.getCodigoActividadReceptor(),
                factura.getTotalIvaDevuelto(),
                otrosCargosEntidades.stream().map(OtrosCargoResponse::desde).toList(),
                referenciasEntidades.stream().map(ReferenciaResponse::desde).toList(),
                mediosPagoEntidades.stream().map(MedioPagoResponse::desde).toList(),
                comprobante.getCodigoRespuesta(),
                comprobante.getMensajeRespuesta(),
                aInstanteUtc(comprobante.getFechaRespuesta()),
                comprobante.getUltimoResultadoConsulta(),
                comprobante.getIntentosEnvio());
    }

    /**
     * {@code ComprobanteElectronico} guarda {@code fechaEmision}/{@code fechaRespuesta} como
     * {@code LocalDateTime} que representa un instante UTC (ver {@code FacturaService} y
     * {@code HaciendaComprobanteApiServiceImpl}), sin ningún indicador de zona. Convertir a
     * {@code Instant} aquí hace que el JSON de salida lleve el sufijo {@code Z} explícito, para que
     * el cliente no tenga que adivinar la zona horaria.
     */
    private static Instant aInstanteUtc(LocalDateTime fecha) {
        return fecha != null ? fecha.toInstant(ZoneOffset.UTC) : null;
    }
}
