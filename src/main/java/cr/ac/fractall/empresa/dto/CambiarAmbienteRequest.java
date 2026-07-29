package cr.ac.fractall.empresa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CambiarAmbienteRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "SANDBOX|PRODUCCION", message = "El ambiente debe ser SANDBOX o PRODUCCION")
        String ambiente) {
}
