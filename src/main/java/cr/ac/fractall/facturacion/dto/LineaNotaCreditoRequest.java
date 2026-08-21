package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Un renglón de {@code POST /notas-credito} (Release 2 / Fase B, ver diseño D-F). A diferencia de
 * {@link LineaFacturaItemRequest} (catálogo), una línea de Nota de Crédito selecciona una línea
 * YA facturada de la factura origen -- {@code productoId}/{@code precioUnitario}/CABYS/impuesto/
 * exoneración/descuentos se copian de esa línea en {@code NotaCreditoDebitoService}, nunca se
 * reprisan. {@code cantidad} es la cantidad a acreditar; debe ser mayor que 0 y no puede exceder
 * la cantidad de la línea origen (regla de negocio 3, validada en el servicio).
 */
public record LineaNotaCreditoRequest(

        @NotNull
        UUID lineaFacturaOrigenId,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "La cantidad debe ser mayor que 0")
        BigDecimal cantidad) {
}
