package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;

/**
 * Proyección agregada por {@code tipo_comprobante} para el comparativo del período anterior
 * (Release 3 / Fase D, ver el diseño obs #918, Q4
 * {@link ReporteFlujoCajaRepository#sumarVentasEnPeriodoPorTipo}, Decisión B4).
 *
 * <p>Solo escalares agregados en SQL -- el comparativo NUNCA materializa filas de detalle del
 * período anterior (Decisión B4: hasta 367 días de detalle descartado sería puro desperdicio). El
 * servicio aplica el mismo {@code signo()} (Decisión B5) fila por fila sobre este agregado, igual
 * que sobre {@link FilaVentaComprobante#tipoComprobante} en el período actual.
 */
public record FilaTotalPorTipoComprobante(
        String tipoComprobante,
        BigDecimal total) {
}
