package cr.ac.fractall.facturacion.dto;

/**
 * Cuerpo compuesto de {@code POST /facturas/{id}/cobros} (Release 3 / Fase C, decisión D3): el
 * cobro recién creado más el saldo/estado recomputado, para que el cliente HTTP no necesite un
 * segundo {@code GET} para conocer el nuevo estado de cobro de la factura.
 */
public record CobroRegistradoResponse(CobroFacturaResponse cobro, FacturaEstadoCobroResponse estado) {
}
