package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Proyección de una fila de cartera pendiente al corte para el reporte de flujo de caja
 * (Release 3 / Fase D, ver el diseño obs #918, Q3
 * {@link ReporteFlujoCajaRepository#buscarCarteraPendienteAlCorte}).
 *
 * <p>Igual que {@link FilaVentaComprobante}/{@link FilaCobroRegistrado}, es el vehículo que
 * {@code ReporteFlujoCajaService} (Fase 4 de este cambio) construye a mano por índice posicional a
 * partir de cada fila {@code Object[]} de la consulta nativa (ver el javadoc de
 * {@link ReporteFlujoCajaRepository}), antes de calcular {@code total = Σ saldoPendiente} y
 * {@code cantidadFacturas = count(saldoPendiente > 0)}.
 *
 * <p>{@code saldoPendiente} llega SIN redondear a piso (nunca clampeado a 0): una factura totalmente
 * acreditada (Requisito "Fully-Credited Invoice Reports as Settled") trae un valor {@code <= 0} en
 * esta fila, no una fila ausente -- el filtro {@code saldoPendiente > 0} para
 * {@code cantidadFacturas} es responsabilidad del servicio (Fase 4), no de esta consulta ni de este
 * record.
 */
public record FilaCarteraFactura(
        UUID facturaId,
        String consecutivo,
        BigDecimal total,
        BigDecimal totalNotaCredito,
        BigDecimal totalNeto,
        BigDecimal totalCobrado,
        BigDecimal saldoPendiente) {
}
