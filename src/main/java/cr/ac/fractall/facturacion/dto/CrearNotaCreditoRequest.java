package cr.ac.fractall.facturacion.dto;

import java.util.List;
import java.util.UUID;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /notas-credito} (Release 2 / Fase B, ver diseño D-E/D-F). Deliberadamente
 * NO reutiliza {@link CrearFacturaRequest} ni {@link ReferenciaRequest}: ninguno de los campos
 * derivados en servidor por las reglas de negocio 4/6 ({@code numero}, {@code clienteId}, {@code
 * tipoDocIr}, {@code fechaEmisionIr}) existe en este DTO -- un cliente que los envíe recibe 400
 * (propiedad JSON desconocida) antes de llegar al servicio, no un campo ignorado silenciosamente.
 *
 * <p>{@code moneda}/{@code tipoCambio}/{@code condicionVenta}/{@code plazoCredito}/{@code
 * medioPago}/{@code codigoActividadReceptor} tampoco están presentes -- se heredan siempre de la
 * factura origen (regla 6), nunca del cliente HTTP.
 */
@OtrosRequiereTexto(codigo = "codigoReferencia", texto = "codigoReferenciaOtro")
public record CrearNotaCreditoRequest(

        @Schema(description = "UUID de la factura electrónica origen (debe estar ACEPTADA y ser tipo 01)")
        @NotNull
        UUID facturaReferenciaId,

        @Schema(description = "Código de referencia (motivo). 01=Anula documento, 02=Corrige monto, ..., 99=Otros.", example = "02")
        @NotBlank
        @Size(max = 2)
        String codigoReferencia,

        @Schema(description = "Texto libre requerido cuando codigoReferencia='99'")
        String codigoReferenciaOtro,

        @Schema(description = "Motivo/razón de la nota de crédito")
        @Size(max = 180)
        String razon,

        @Schema(description = "Líneas a acreditar, seleccionadas de la factura origen (al menos una requerida)")
        @NotEmpty
        @Valid
        List<LineaNotaCreditoRequest> lineas) {
}
