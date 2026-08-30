package cr.ac.fractall.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cartera pendiente punto-en-el-tiempo (Release 3 / Fase D, ver el diseño obs #918, Decisiones B8/
 * D3). {@code fechaCorte} vive ÚNICAMENTE aquí -- nunca como campo de nivel superior en
 * {@link ReporteFlujoCajaResponse} (Decisión B8: dos campos con el mismo valor derivado son dos
 * lugares de desincronizarse) -- y es SIEMPRE {@code hasta} del período solicitado, nunca un
 * parámetro propio (Requisito "fechaCorte Derived From hasta, Never a Separate Parameter").
 *
 * <p>{@code total} = Σ {@code saldo_pendiente} sin redondear a piso; {@code cantidadFacturas}
 * cuenta solo facturas con {@code saldo_pendiente > 0} -- una factura totalmente acreditada
 * ({@code total_neto <= 0}) no cuenta como pendiente (Requisito "Fully-Credited Invoice Reports as
 * Settled").
 */
public record CarteraPendiente(
        LocalDate fechaCorte,
        BigDecimal total,
        long cantidadFacturas) {
}
