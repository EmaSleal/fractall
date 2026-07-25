package cr.ac.fractall.facturacion.dto;

import java.time.LocalDate;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@OtrosRequiereTexto(codigo = "tipoDocIr", texto = "tipoDocRefOtro")
@OtrosRequiereTexto(codigo = "codigo", texto = "codigoReferenciaOtro")
public record ReferenciaRequest(

        @NotBlank
        String tipoDocIr,

        String tipoDocRefOtro,

        String numero,

        @NotNull
        LocalDate fechaEmisionIr,

        @NotBlank
        @Size(max = 2)
        String codigo,

        String codigoReferenciaOtro,

        @Size(max = 180)
        String razon) {
}
