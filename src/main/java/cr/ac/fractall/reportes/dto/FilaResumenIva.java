package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;

/**
 * Fila agregada del reporte de IVA (Release 3 / Fase D), agrupada por la clave
 * {@code (gravado, porcentaje)} -- ver el diseño, decisión A7: exento y gravado-al-0% son filas
 * separadas aunque ambas den impuesto cero, porque representan hechos fiscales distintos.
 *
 * <p>Deliberadamente sin campo {@code medioPago} -- ver spec, requisito "No `medio_pago` Field".
 */
public record FilaResumenIva(
        boolean gravado,
        BigDecimal porcentajeImpuesto,
        BigDecimal baseImponible,
        BigDecimal impuestoBruto,
        BigDecimal exoneraciones,
        BigDecimal impuestoNeto) {
}
