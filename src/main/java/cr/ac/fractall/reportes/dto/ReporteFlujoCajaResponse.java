package cr.ac.fractall.reportes.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta de {@code GET /reportes/flujo-caja} (Release 3 / Fase D, Change 2 de 2, ver el diseño
 * obs #918). {@code JSON} es el artefacto autoritativo; los exports PDF/Excel son proyecciones de
 * esta misma estructura.
 *
 * <p>Ventas y cobros son series INDEPENDIENTES, nunca sumadas en una sola cifra. {@code cartera}
 * lleva su propio {@code fechaCorte} (Decisión B8) -- no hay campo de corte de nivel superior.
 */
public record ReporteFlujoCajaResponse(
        LocalDate desde,
        LocalDate hasta,
        SerieVentas ventas,
        SerieCobros cobros,
        CarteraPendiente cartera,
        ComparativoPeriodoAnterior comparativo,
        List<FilaDetalleVenta> detalleVentas,
        List<FilaDetalleCobro> detalleCobros) {
}
