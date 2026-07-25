package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@OtrosRequiereTexto(codigo = "tipoMedioPago", texto = "medioPagoOtros")
public record MedioPagoRequest(

        @NotBlank
        String tipoMedioPago,

        @Size(min = 3, max = 100)
        String medioPagoOtros,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal totalMedioPago) {
}
