package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Comparativo del período inmediatamente anterior, mismo día-cuenta, adyacente y sin solape
 * (Release 3 / Fase D, ver el diseño obs #918, Decisiones B4/D4). Cálculo basado en día-cuenta, NO
 * calendario -- el caso de febrero (desplaza a un rango no-calendario de enero) es comportamiento
 * documentado y confirmado, no un defecto.
 *
 * <p>{@code variacionVentas}/{@code variacionCobros} son deltas ABSOLUTOS, nunca porcentuales.
 */
public record ComparativoPeriodoAnterior(
        LocalDate desdeAnterior,
        LocalDate hastaAnterior,
        BigDecimal ventasAnterior,
        BigDecimal cobrosAnterior,
        BigDecimal variacionVentas,
        BigDecimal variacionCobros) {
}
