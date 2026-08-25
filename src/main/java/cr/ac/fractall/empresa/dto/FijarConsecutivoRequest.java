package cr.ac.fractall.empresa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record FijarConsecutivoRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "SANDBOX|PRODUCCION", message = "El ambiente debe ser SANDBOX o PRODUCCION")
        String ambiente,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "01|02|03|04", message = "Tipo de comprobante inválido")
        String tipoComprobante,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive
        long nuevoValor) {
}
