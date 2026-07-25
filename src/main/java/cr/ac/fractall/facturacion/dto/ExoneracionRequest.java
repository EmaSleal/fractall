package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@OtrosRequiereTexto(codigo = "tipoDocumentoEx1", texto = "tipoDocumentoOtro")
@OtrosRequiereTexto(codigo = "nombreInstitucion", texto = "nombreInstitucionOtros")
public record ExoneracionRequest(

        @NotBlank
        String tipoDocumentoEx1,

        String tipoDocumentoOtro,

        @NotBlank
        @Size(min = 3, max = 40)
        String numeroDocumento,

        @Max(999999)
        Integer articulo,

        @Max(999999)
        Integer inciso,

        @NotBlank
        String nombreInstitucion,

        String nombreInstitucionOtros,

        @NotNull
        LocalDate fechaEmisionEx,

        @NotNull
        BigDecimal tarifaExonerada,

        @NotNull
        @DecimalMin(value = "0")
        BigDecimal montoExoneracion) {
}
