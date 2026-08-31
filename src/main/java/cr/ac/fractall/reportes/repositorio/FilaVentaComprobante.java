package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Proyección de una fila de venta para el reporte de flujo de caja (Release 3 / Fase D, ver el
 * diseño obs #918, Q1 {@link ReporteFlujoCajaRepository#buscarVentasEnPeriodo}).
 *
 * <p>NO representa el resultado directo de la consulta nativa (esa devuelve {@code Object[]}, ver
 * el javadoc de {@link ReporteFlujoCajaRepository} y la misma advertencia en
 * {@code FacturaRepository:31-32}): este record es el vehículo que {@code ReporteFlujoCajaService}
 * (Fase 4 de este cambio) construye a mano por índice posicional a partir de cada fila, antes de
 * aplicar {@code signo()} (Decisión B5) y foldearla en {@code SerieVentas}/{@code FilaDetalleVenta}.
 *
 * <p>Deliberadamente SIN {@code condicionVenta} restringida ni filtro de {@code tipo_comprobante}
 * en la consulta que lo produce (Requisito "Ventas Series Includes All condicion_venta Values",
 * D2): incluye toda venta y todo ajuste (NC/ND) del período, sin excepción.
 */
public record FilaVentaComprobante(
        UUID facturaId,
        String tipoComprobante,
        String consecutivo,
        LocalDateTime fechaEmision,
        String condicionVenta,
        UUID clienteId,
        String moneda,
        UUID facturaReferenciaId,
        BigDecimal total) {
}
