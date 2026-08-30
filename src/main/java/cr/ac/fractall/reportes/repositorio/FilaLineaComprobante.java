package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Proyección de una línea de comprobante para el reporte de IVA (Release 3 / Fase D), producida
 * por {@link ReporteIvaRepository#buscarLineasEnPeriodo} vía constructor-expression JPQL.
 *
 * <p>Denormaliza exactamente los campos que {@code CalculadoraImpuestoLinea#calcular} necesita
 * ({@code subtotal}, {@code porcentajeImpuestoAplicado}, {@code exoneracionId},
 * {@code montoExoneracionAplicado}) más los campos de encabezado del comprobante/factura que
 * alimentan {@code FilaDetalleIva} -- ver el diseño, sección "Interfaces / Contracts". No hay
 * asociaciones JPA en este codebase (todas las FK son {@code UUID} planos), así que este record es
 * el único vehículo entre el theta-join de 3 entidades y el traversal en memoria de
 * {@code ReporteIvaService}.
 */
public record FilaLineaComprobante(
        UUID comprobanteId,
        String tipoComprobante,
        String consecutivo,
        String claveNumerica,
        LocalDateTime fechaEmision,
        UUID facturaId,
        UUID clienteId,
        String moneda,
        UUID facturaReferenciaId,
        UUID lineaId,
        int numeroLinea,
        BigDecimal subtotal,
        boolean gravadoAplicado,
        BigDecimal porcentajeImpuestoAplicado,
        UUID exoneracionId,
        BigDecimal montoExoneracionAplicado) {
}
