package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta de {@code GET /reportes/iva} (Release 3 / Fase D, ver el diseño). {@code JSON} es el
 * artefacto fiscal autoritativo; los exports PDF/Excel son proyecciones de esta misma estructura.
 *
 * <p>Deliberadamente sin campo {@code medioPago} en ningún nivel -- ver spec, requisito
 * "No `medio_pago` Field".
 */
public record ReporteIvaResponse(
        LocalDate desde,
        LocalDate hasta,
        List<FilaResumenIva> resumen,
        List<FilaDetalleIva> detalle,
        BigDecimal totalDebitoFiscal) {
}
