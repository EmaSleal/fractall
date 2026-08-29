package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fila de detalle del reporte de IVA (Release 3 / Fase D): una fila por línea de transacción, sin
 * agregar -- consumida tal cual por la hoja {@code Detalle} del export Excel (ver el diseño).
 *
 * <p>{@code subtotal}/{@code impuestoBruto}/{@code montoExoneracion}/{@code impuestoNeto} son los
 * montos SIN signo devueltos por {@code CalculadoraImpuestoLinea#calcular}; {@code signo} expone
 * por separado la dirección (+1 factura/tiquete/ND, -1 NC) que {@code ReporteIvaService} aplicó al
 * acumular esta línea en el resumen -- separar ambos evita esconder el signo dentro de un monto ya
 * "neteado", que sería ambiguo de leer en un documento fiscal.
 *
 * <p>Deliberadamente sin campo {@code medioPago} -- ver spec, requisito "No `medio_pago` Field".
 */
public record FilaDetalleIva(
        LocalDate fechaEmision,
        String tipoComprobante,
        String consecutivo,
        String claveNumerica,
        UUID facturaId,
        UUID clienteId,
        UUID facturaReferenciaId,
        int numeroLinea,
        boolean gravado,
        BigDecimal porcentajeImpuesto,
        BigDecimal subtotal,
        BigDecimal impuestoBruto,
        BigDecimal montoExoneracion,
        BigDecimal impuestoNeto,
        int signo) {
}
