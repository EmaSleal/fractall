package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO de ítem de lista para GET /facturas. Instanciado directamente por la expresión constructora
 * JPQL de {@code FacturaRepository#buscar} — no tiene factory method separado.
 *
 * <p>Los campos derivados de {@code ComprobanteElectronico} ({@code consecutivo}, {@code estado},
 * {@code ambienteHacienda}, {@code ultimoResultadoConsulta}, {@code fechaEmision}) son nullable:
 * una factura en estado GENERADO sin comprobante aún emitido aparece con esos campos en null
 * (LEFT JOIN en la consulta). Req: NFR-5.
 *
 * <p>El constructor secundario que acepta {@code LocalDateTime} existe para que la expresión
 * constructora JPQL pueda pasar {@code ce.fechaEmision} (que Hibernate 7 resuelve como
 * {@code LocalDateTime}) y la conversión a {@code LocalDate} ocurra aquí, evitando un
 * {@code SemanticException: Missing constructor} en la validación de la consulta en startup.
 */
public record FacturaResumenResponse(
        UUID id,
        String consecutivo,
        UUID clienteId,
        String clienteNombre,
        String ambienteHacienda,
        String moneda,
        BigDecimal total,
        String estado,
        String ultimoResultadoConsulta,
        LocalDate fechaEmision) {

    /** Constructor secundario para JPQL: convierte {@code LocalDateTime} a {@code LocalDate}. */
    public FacturaResumenResponse(
            UUID id,
            String consecutivo,
            UUID clienteId,
            String clienteNombre,
            String ambienteHacienda,
            String moneda,
            BigDecimal total,
            String estado,
            String ultimoResultadoConsulta,
            java.time.LocalDateTime fechaEmisionDt) {
        this(id, consecutivo, clienteId, clienteNombre, ambienteHacienda, moneda, total,
                estado, ultimoResultadoConsulta,
                fechaEmisionDt != null ? fechaEmisionDt.toLocalDate() : null);
    }
}
