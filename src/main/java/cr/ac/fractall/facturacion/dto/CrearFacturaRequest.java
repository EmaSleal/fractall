package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
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
 * {@code 'CRC'}, {@code 1.00000} respectivamente).
 *
 * <p>{@code medioPago} (String) is deprecated — use {@code mediosPago} instead.
 * Legacy field is kept for backward compatibility; service falls back to it when mediosPago empty.
 */
@OtrosRequiereTexto(codigo = "condicionVenta", texto = "condicionVentaOtros")
public record CrearFacturaRequest(

        @NotNull
        UUID clienteId,

        @Size(max = 2)
        String condicionVenta,

        Integer plazoCredito,

        @Size(max = 100)
        String condicionVentaOtros,

        @Size(min = 6, max = 6)
        String codigoActividadReceptor,

        BigDecimal totalIvaDevuelto,

        @Size(max = 2)
        String medioPago,

        @Size(max = 3)
        String moneda,

        BigDecimal tipoCambio,

        @NotEmpty
        @Valid
        List<LineaFacturaItemRequest> lineas,

        @Size(max = 4)
        @Valid
        List<MedioPagoRequest> mediosPago,

        @Size(max = 15)
        @Valid
        List<OtrosCargoRequest> otrosCargos,

        @Size(max = 10)
        @Valid
        List<ReferenciaRequest> informacionReferencia) {

    @AssertTrue(message = "plazoCredito es obligatorio cuando condicionVenta es '02'")
    boolean isPlazoCreditoValido() {
        return !"02".equals(condicionVenta) || plazoCredito != null;
    }
}
