package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Serie de ventas del período (Release 3 / Fase D, ver el diseño obs #918). Incluye TODO
 * {@code condicion_venta} presente en el período, incluyendo {@code '01'} contado (Requisito
 * "Ventas Series Includes All condicion_venta Values") -- nunca se suma con {@link SerieCobros};
 * ambas series se reportan por separado.
 *
 * <p>{@code total}/{@code total} de cada {@link FilaVentasPorCondicion} ya llevan el signo aplicado
 * (Decisión B5): {@code cantidadComprobantes} cuenta documentos (facturas, tiquetes, NC, ND), no
 * "facturas" -- una NC/ND es su propio documento, no una factura.
 */
public record SerieVentas(
        BigDecimal total,
        long cantidadComprobantes,
        List<FilaVentasPorCondicion> porCondicionVenta) {
}
