package cr.ac.fractall.facturacion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IdentificacionTerceroRequest(

        @NotBlank
        @Size(max = 2)
        String tipo,

        @NotBlank
        @Size(max = 20)
        String numero) {
}
