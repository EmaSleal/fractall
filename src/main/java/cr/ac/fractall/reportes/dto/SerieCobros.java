package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Serie de cobros del período (Release 3 / Fase D, ver el diseño obs #918). Nunca se suma con
 * {@link SerieVentas}; ambas series se reportan por separado, nunca combinadas en una sola cifra.
 */
public record SerieCobros(
        BigDecimal total,
        long cantidadCobros,
        List<FilaCobrosPorMedioPago> porMedioPago) {
}
