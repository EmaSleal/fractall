package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.validacion.OtrosRequiereTexto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@OtrosRequiereTexto(codigo = "codigoDescuento", texto = "codigoDescuentoOtro")
public record DescuentoRequest(

        @NotNull
        @DecimalMin(value = "0")
        BigDecimal montoDescuento,

        @Size(max = 2)
        String codigoDescuento,

        @Size(min = 5, max = 100)
        String codigoDescuentoOtro,

        @Size(min = 3, max = 80)
        String naturalezaDescuento) {
}
