package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;

/**
 * Fila agregada de cobros por {@code medio_pago} (Release 3 / Fase D, ver el diseño obs #918).
 *
 * <p>{@code medioPago} viene de {@code cobro_factura.medio_pago}, NUNCA de
 * {@code factura.medio_pago} (Requisito "Cobros Series Groups by cobro_factura.medio_pago Only").
 * {@code descripcionMedioPago} es la etiqueta humana resuelta por
 * {@code ReporteFlujoCajaService#descripcionMedioPago} (Decisión B6, fail-closed) -- un código no
 * reconocido nunca llega a existir como fila, la generación completa del reporte falla antes.
 */
public record FilaCobrosPorMedioPago(
        String medioPago,
        String descripcionMedioPago,
        long cantidadCobros,
        BigDecimal total) {
}
