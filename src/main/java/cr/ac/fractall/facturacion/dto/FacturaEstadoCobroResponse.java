package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;

/** Proyección de la vista {@code factura_estado_cobro} (Release 3 / Fase C, NC-neteada). */
public record FacturaEstadoCobroResponse(
        UUID facturaId,
        BigDecimal total,
        BigDecimal totalNotaCredito,
        BigDecimal totalNeto,
        BigDecimal totalCobrado,
        BigDecimal saldoPendiente,
        String estadoCobro) {

    public static FacturaEstadoCobroResponse desde(FacturaEstadoCobro estado) {
        return new FacturaEstadoCobroResponse(
                estado.getFacturaId(),
                estado.getTotal(),
                estado.getTotalNotaCredito(),
                estado.getTotalNeto(),
                estado.getTotalCobrado(),
                estado.getSaldoPendiente(),
                estado.getEstadoCobro());
    }
}
