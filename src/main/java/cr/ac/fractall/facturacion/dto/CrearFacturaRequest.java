package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * {@code 'CRC'}, {@code 1.00000} respectivamente). {@code tipoCambio} tiene una excepción a ese
 * default: si {@code moneda='USD'} y se omite, {@code FacturaService} lo autocompleta con el
 * tipo de cambio de venta del día publicado por Hacienda (no con {@code 1.00000}) -- ver
 * {@code FacturaService#resolverTipoCambio}. Para cualquier otra moneda distinta de CRC/USD,
 * {@code tipoCambio} es obligatorio ({@link #isTipoCambioValido}): no existe integración con
 * ningún otro tipo de cambio.
 *
 * <p>{@code medioPago} (String) is deprecated — use {@code mediosPago} instead.
 * Legacy field is kept for backward compatibility; service falls back to it when mediosPago empty.
 */
@OtrosRequiereTexto(codigo = "condicionVenta", texto = "condicionVentaOtros")
public record CrearFacturaRequest(

        @Schema(description = "UUID of the target client", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull
        UUID clienteId,

        @Schema(description = "Sale condition code (default '01' — cash). 01=Contado, 02=Crédito, 03=Consignación, 04=Apartado, 05=Arrendamiento con opción de compra, 06=Arrendamiento en función financiera, 99=Otros.", example = "01")
        @Size(max = 2)
        String condicionVenta,

        @Schema(description = "Credit term in days; required when condicionVenta='02'", example = "30")
        Integer plazoCredito,

        @Schema(description = "Free-text description of sale condition; required when condicionVenta='99'", example = "Pago en especie")
        @Size(max = 100)
        String condicionVentaOtros,

        @Schema(description = "Receptor economic activity code (6 digits); required when the receptor is a taxpayer", example = "620100")
        @Size(min = 6, max = 6)
        String codigoActividadReceptor,

        @Schema(description = "Total VAT returned to the client (IVA devuelto)", example = "0.00")
        BigDecimal totalIvaDevuelto,

        @Schema(description = "Deprecated — use mediosPago list instead. Payment method code (max 2 chars).", example = "01", deprecated = true)
        @Size(max = 2)
        String medioPago,

        @Schema(description = "Currency code (ISO 4217). Defaults to 'CRC' if omitted.", example = "CRC")
        @Size(max = 3)
        String moneda,

        @Schema(description = "Exchange rate to CRC. Defaults to 1.00000 when moneda is 'CRC' or omitted; auto-filled from Hacienda's daily rate when moneda='USD' and omitted; required explicitly for any other currency.", example = "530.50")
        BigDecimal tipoCambio,

        @Schema(description = "Invoice line items (at least one required)")
        @NotEmpty
        @Valid
        List<LineaFacturaItemRequest> lineas,

        @Schema(description = "List of payment methods (up to 4). Preferred over the deprecated medioPago field.")
        @Size(max = 4)
        @Valid
        List<MedioPagoRequest> mediosPago,

        @Schema(description = "Other charges to include in the invoice (up to 15)")
        @Size(max = 15)
        @Valid
        List<OtrosCargoRequest> otrosCargos,

        @Schema(description = "Reference documents linked to this invoice (up to 10)")
        @Size(max = 10)
        @Valid
        List<ReferenciaRequest> informacionReferencia) {

    @AssertTrue(message = "plazoCredito es obligatorio cuando condicionVenta es '02'")
    boolean isPlazoCreditoValido() {
        return !"02".equals(condicionVenta) || plazoCredito != null;
    }

    @AssertTrue(message = "tipoCambio es obligatorio cuando moneda no es 'CRC' ni 'USD' (el tipo de cambio del dólar se autocompleta; otras monedas no)")
    boolean isTipoCambioValido() {
        return tipoCambio != null || moneda == null || "CRC".equals(moneda) || "USD".equals(moneda);
    }
}
