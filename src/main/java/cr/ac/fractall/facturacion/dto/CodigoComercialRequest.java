package cr.ac.fractall.facturacion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CodigoComercialRequest(

        @NotBlank
        @Size(max = 2)
        String tipo,

        @NotBlank
        @Size(max = 20)
        String codigo) {
}
