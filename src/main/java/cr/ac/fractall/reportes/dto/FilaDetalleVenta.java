package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fila de detalle de venta, una por comprobante, sin agregar (Release 3 / Fase D, ver el diseño
 * obs #918). Consumida tal cual por la hoja {@code DetalleVentas} del export Excel.
 *
 * <p>{@code total} es el monto SIN signo de {@code FilaVentaComprobante}; {@code signo} expone por
 * separado la dirección ({@code ReporteFlujoCajaService#signo}) -- mismo criterio que
 * {@code FilaDetalleIva.signo}, así que esta hoja suma a su fila de {@link SerieVentas}/
 * {@link FilaVentasPorCondicion} correspondiente.
 */
public record FilaDetalleVenta(
        LocalDate fechaEmision,
        String tipoComprobante,
        String consecutivo,
        String condicionVenta,
        UUID facturaId,
        UUID clienteId,
        UUID facturaReferenciaId,
        String moneda,
        BigDecimal total,
        int signo) {
}
