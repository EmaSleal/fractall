package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /catalogo/clientes/{clienteId}/exoneraciones} (sección 4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}). {@code nombreInstitucionOtros} solo es
 * obligatorio cuando {@code tipoDocumento = '99'} -- validado en
 * {@code ClienteExoneracionService}, no aquí (depende del valor de otro campo del mismo
 * request).
 */
public record CrearClienteExoneracionRequest(

        @NotBlank
        @Size(max = 2)
        String tipoDocumento,

        @NotBlank
        @Size(max = 40)
        String numeroDocumento,

        @NotBlank
        @Size(max = 160)
        String nombreInstitucion,

        @NotBlank
        @Size(max = 10)
        String numeroArticulo,

        @Size(max = 10)
        String inciso,

        @Size(max = 160)
        String nombreInstitucionOtros,

        @NotNull
        LocalDateTime fechaEmision,

        LocalDateTime fechaVencimiento,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "El porcentaje de exoneración debe ser mayor que 0")
        @DecimalMax(value = "100", message = "El porcentaje de exoneración no puede superar 100")
        BigDecimal porcentajeExoneracion) {
}
