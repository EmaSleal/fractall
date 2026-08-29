package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.facturacion.modelo.CobroFactura;

/** Proyección de un registro {@code cobro_factura} (Release 3 / Fase C). */
public record CobroFacturaResponse(
        UUID id,
        UUID facturaId,
        BigDecimal montoCobrado,
        LocalDateTime fechaCobro,
        String medioPago,
        String referencia,
        UUID registradoPor,
        LocalDateTime createDate) {

    public static CobroFacturaResponse desde(CobroFactura cobro) {
        return new CobroFacturaResponse(
                cobro.getId(),
                cobro.getFacturaId(),
                cobro.getMontoCobrado(),
                cobro.getFechaCobro(),
                cobro.getMedioPago(),
                cobro.getReferencia(),
                cobro.getRegistradoPor(),
                cobro.getCreateDate());
    }
}
