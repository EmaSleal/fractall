package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@OtrosRequiereTexto(codigo = "tipoDocumentoOc", texto = "tipoDocumentoOtros")
public record OtrosCargoRequest(

        @NotBlank
        String tipoDocumentoOc,

        @Size(min = 5, max = 100)
        String tipoDocumentoOtros,

        @Valid
        IdentificacionTerceroRequest identificacionTercero,

        @Size(max = 100)
        String nombreTercero,

        @NotBlank
        @Size(max = 200)
        String detalle,

        BigDecimal porcentajeOc,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal montoCargo) {
}
