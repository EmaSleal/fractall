package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un renglón de {@code POST /facturas} (sección 4.14 de
 * {@code arquitectura-facturacion-electronica-cr.md}). {@code exoneracionId} es opcional -- su
 * ausencia significa línea sin exoneración; su presencia dispara las 3 validaciones explícitas de
 * {@code FacturaService#crear} (pertenencia al cliente, vigencia, tipo de documento aplicable).
 *
 * <p>{@code exoneracion} (inline block) takes precedence over {@code exoneracionId} (legacy FK
 * path) when both are present. When only {@code exoneracion} is provided, the legacy
 * ClienteExoneracion path is skipped entirely.
 */
public record LineaFacturaItemRequest(

        @NotNull
        UUID productoId,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "La cantidad debe ser mayor que 0")
        BigDecimal cantidad,

        @NotNull
        @DecimalMin(value = "0", message = "El precio unitario no puede ser negativo")
        BigDecimal precioUnitario,

        UUID exoneracionId,

        @Size(max = 5)
        @Valid
        List<CodigoComercialRequest> codigosComerciales,

        @Size(max = 5)
        @Valid
        List<DescuentoRequest> descuentos,

        @Size(max = 2)
        String tipoTransaccion,

        @Size(max = 20)
        String unidadMedidaComercial,

        BigDecimal ivaCobradoFabrica,

        BigDecimal factorCalculoIva,

        @Valid
        ExoneracionRequest exoneracion) {
}
