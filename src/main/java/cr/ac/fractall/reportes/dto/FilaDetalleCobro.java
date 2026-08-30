package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fila de detalle de cobro, una por {@code cobro_factura}, sin agregar (Release 3 / Fase D, ver el
 * diseño obs #918). Consumida tal cual por la hoja {@code DetalleCobros} del export Excel.
 *
 * <p>{@code medioPago}/{@code descripcionMedioPago} vienen de {@code cobro_factura.medio_pago}
 * resuelto por {@code ReporteFlujoCajaService#descripcionMedioPago} (Decisión B6, fail-closed).
 * Sin campo {@code signo}: a diferencia de ventas, un cobro nunca resta -- no existe un "cobro
 * negativo" equivalente a una Nota de Crédito.
 */
public record FilaDetalleCobro(
        LocalDate fechaCobro,
        UUID cobroId,
        UUID facturaId,
        String consecutivoFactura,
        String condicionVenta,
        String medioPago,
        String descripcionMedioPago,
        String referencia,
        BigDecimal montoCobrado) {
}
