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
 * Cuerpo de {@code POST /notas-debito} (Release 2 / Fase B, ver diseño D-E/D-F). A diferencia de
 * {@link CrearNotaCreditoRequest}, sus líneas se arman desde catálogo (mismo shape que {@link
 * CrearFacturaRequest}), vía {@link LineaFacturaItemRequest} reutilizado tal cual -- ningún campo
 * de ese DTO es incorrecto en este contexto. No hay tope de monto (regla 3 no aplica a ND).
 *
 * <p>Deliberadamente NO reutiliza {@link CrearFacturaRequest}: ese DTO expone {@code clienteId}
 * (prohibido por la regla 6) y campos de nivel-factura ({@code moneda}/{@code tipoCambio}/{@code
 * condicionVenta}/{@code mediosPago}) que aquí siempre se heredan de la factura origen.
 */
@OtrosRequiereTexto(codigo = "codigoReferencia", texto = "codigoReferenciaOtro")
public record CrearNotaDebitoRequest(

        @Schema(description = "UUID de la factura electrónica origen (debe estar ACEPTADA y ser tipo 01)")
        @NotNull
        UUID facturaReferenciaId,

        @Schema(description = "Código de referencia (motivo). 01=Anula documento, 02=Corrige monto, ..., 99=Otros.", example = "05")
        @NotBlank
        @Size(max = 2)
        String codigoReferencia,

        @Schema(description = "Texto libre requerido cuando codigoReferencia='99'")
        String codigoReferenciaOtro,

        @Schema(description = "Motivo/razón de la nota de débito")
        @Size(max = 180)
        String razon,

        @Schema(description = "Líneas desde catálogo (al menos una requerida)")
        @NotEmpty
        @Valid
        List<LineaFacturaItemRequest> lineas) {
}
