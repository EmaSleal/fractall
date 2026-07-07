package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /facturas} (Fase 7, secciones 4.12-4.14 de
 * {@code arquitectura-facturacion-electronica-cr.md}). {@code empresaId} nunca llega aquí --
 * {@code FacturaService} lo resuelve de {@code TenantContext}, mismo patrón que
 * {@code ProductoController}/{@code ClienteController}.
 *
 * <p>{@code condicionVenta}/{@code medioPago}/{@code moneda}/{@code tipoCambio} son opcionales:
 * si se omiten, {@code FacturaService} aplica los mismos valores por defecto que
 * {@code V4__catalogo_y_facturacion.sql} define a nivel de columna ({@code '01'}, {@code '01'},
 * {@code 'CRC'}, {@code 1.00000} respectivamente).
 */
public record CrearFacturaRequest(

        @NotNull
        UUID clienteId,

        @Size(max = 2)
        String condicionVenta,

        Integer plazoCredito,

        @Size(max = 2)
        String medioPago,

        @Size(max = 3)
        String moneda,

        BigDecimal tipoCambio,

        @NotEmpty
        @Valid
        List<LineaFacturaItemRequest> lineas) {
}
