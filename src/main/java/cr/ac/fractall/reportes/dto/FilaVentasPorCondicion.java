package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;

/**
 * Fila agregada de ventas por {@code condicion_venta} (Release 3 / Fase D, ver el diseño obs #918).
 *
 * <p>{@code total} lleva el monto YA signado ({@code ReporteFlujoCajaService#signo}, Decisión B5):
 * una Nota de Crédito resta del bucket de su propia {@code condicion_venta} heredada, nunca del
 * bucket de contado ({@code '01'}).
 */
public record FilaVentasPorCondicion(
        String condicionVenta,
        long cantidadComprobantes,
        BigDecimal total) {
}
