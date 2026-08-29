package cr.ac.fractall.facturacion.dto;

import java.util.List;

/**
 * Cuerpo de {@code GET /facturas/{id}/cobros} (Release 3 / Fase C): el estado/saldo actual más el
 * historial ordenado de cobros previos.
 */
public record HistorialCobrosResponse(FacturaEstadoCobroResponse estado, List<CobroFacturaResponse> cobros) {
}
