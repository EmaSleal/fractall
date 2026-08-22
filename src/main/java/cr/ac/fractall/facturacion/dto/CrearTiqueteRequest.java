package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /tiquetes} (Release 2 / Fase C, ver {@code docs/plan-fases-release-2.md}).
 * Un Tiquete Electrónico no referencia ningún documento previo -- es una venta nueva, mismo shape
 * de nivel-factura y de líneas que {@link CrearFacturaRequest} (vía {@link LineaFacturaItemRequest}
 * reutilizado tal cual), no el de {@link CrearNotaDebitoRequest} (que hereda cliente/moneda/
 * condición de venta de una factura origen que Tiquete no tiene).
 *
 * <p>Dos diferencias deliberadas contra {@link CrearFacturaRequest}:
 * <ul>
 *   <li>{@code clienteId} es OPCIONAL (sin {@code @NotNull}) -- un Tiquete puede emitirse sin
 *       receptor identificado (venta de mostrador, regla confirmada de Fase C). Cuando se informa,
 *       {@code TiqueteService} valida que exista y pertenezca al tenant, igual que
 *       {@code FacturaService#crear}.
 *   <li>Sin {@code codigoActividadReceptor} ni {@code informacionReferencia}: el perfil {@code
 *       TipoComprobantePerfil.TIQUETE} (Fase B) tiene {@code codigoActividadReceptorSoportado
 *       = false} (el XSD de Tiquete no lo declara), y un Tiquete no referencia ningún documento
 *       previo -- ese bloque es semánticamente para NC/ND o para una Factura que sí lo necesite.
 * </ul>
 *
 * <p>{@code otrosCargos}/{@code mediosPago} (lista) tampoco están presentes -- alcance deliberado
 * de Fase C: un único medio de pago legado ({@code medioPago}) es suficiente para el caso de uso
 * de venta de mostrador; puede ampliarse sin romper compatibilidad si aparece un caso de uso real.
 */
@OtrosRequiereTexto(codigo = "condicionVenta", texto = "condicionVentaOtros")
public record CrearTiqueteRequest(

        @Schema(description = "UUID del cliente receptor; opcional -- ausente para venta de mostrador sin receptor identificado", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID clienteId,

        @Schema(description = "Condición de venta (default '01' -- contado). 01=Contado, 02=Crédito, ..., 99=Otros.", example = "01")
        @Size(max = 2)
        String condicionVenta,

        @Schema(description = "Plazo de crédito en días; obligatorio cuando condicionVenta='02'", example = "30")
        Integer plazoCredito,

        @Schema(description = "Texto libre de condición de venta; obligatorio cuando condicionVenta='99'")
        @Size(max = 100)
        String condicionVentaOtros,

        @Schema(description = "Total de IVA devuelto al cliente", example = "0.00")
        BigDecimal totalIvaDevuelto,

        @Schema(description = "Código de medio de pago (default '01' -- efectivo)", example = "01")
        @Size(max = 2)
        String medioPago,

        @Schema(description = "Código de moneda ISO 4217 (default 'CRC')", example = "CRC")
        @Size(max = 3)
        String moneda,

        @Schema(description = "Tipo de cambio a CRC; obligatorio para cualquier moneda distinta de CRC/USD", example = "530.50")
        BigDecimal tipoCambio,

        @Schema(description = "Líneas del tiquete (al menos una requerida)")
        @NotEmpty
        @Valid
        List<LineaFacturaItemRequest> lineas) {

    @AssertTrue(message = "plazoCredito es obligatorio cuando condicionVenta es '02'")
    boolean isPlazoCreditoValido() {
        return !"02".equals(condicionVenta) || plazoCredito != null;
    }

    @AssertTrue(message = "tipoCambio es obligatorio cuando moneda no es 'CRC' ni 'USD'")
    boolean isTipoCambioValido() {
        return tipoCambio != null || moneda == null || "CRC".equals(moneda) || "USD".equals(moneda);
    }
}
