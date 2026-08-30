package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Proyección de una fila de cobro para el reporte de flujo de caja (Release 3 / Fase D, ver el
 * diseño obs #918, Q2 {@link ReporteFlujoCajaRepository#buscarCobrosEnPeriodo}).
 *
 * <p>Igual que {@link FilaVentaComprobante}, es el vehículo que {@code ReporteFlujoCajaService}
 * construye a mano por índice posicional a partir de cada fila {@code Object[]} de la consulta
 * nativa (ver el javadoc de {@link ReporteFlujoCajaRepository}), antes de resolver
 * {@code descripcionMedioPago()} (Decisión B6) y foldearla en
 * {@code SerieCobros}/{@code FilaDetalleCobro}.
 *
 * <p>{@code medioPago} viene de {@code cobro_factura.medio_pago}, NUNCA de
 * {@code factura.medio_pago} (Requisito "Cobros Series Groups by cobro_factura.medio_pago Only",
 * D6) -- por eso este record no tiene ningún campo derivado de la factura salvo
 * {@code condicionVenta}/{@code consecutivoFactura}, que son metadatos de exhibición, no de
 * agrupación.
 */
public record FilaCobroRegistrado(
        UUID cobroId,
        LocalDateTime fechaCobro,
        String medioPago,
        BigDecimal montoCobrado,
        String referencia,
        UUID facturaId,
        String condicionVenta,
        String consecutivoFactura) {
}
