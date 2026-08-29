package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /facturas/{id}/cobros} (Release 3 / Fase C, ver diseño de
 * {@code cobro_factura}, decisión D4/I). Deliberadamente NO expone {@code registradoPor}: ese
 * campo sale siempre del principal autenticado en {@code CobroFacturaService#registrar} -- un
 * cliente que envíe un campo con esa forma en el cuerpo no tiene efecto alguno.
 *
 * <p>{@code fechaCobro} es opcional (decisión I): {@code null} hace que el servicio use
 * {@code now()}. {@code @Digits(integer = 9, fraction = 5)} refleja {@code NUMERIC(14,5)}.
 */
public record RegistrarCobroRequest(

        @Schema(description = "Monto cobrado, mayor a cero")
        @NotNull
        @DecimalMin(value = "0.00001")
        @Digits(integer = 9, fraction = 5)
        BigDecimal montoCobrado,

        @Schema(description = "Código de medio de pago (catálogo FE v4.4)", example = "04")
        @NotBlank
        @Size(min = 2, max = 2)
        String medioPago,

        @Schema(description = "Detalle libre, usado por ejemplo cuando medioPago='99 Otros'")
        @Size(max = 100)
        String referencia,

        @Schema(description = "Fecha en que se recibió el cobro; si se omite, se usa la fecha/hora actual")
        @PastOrPresent
        LocalDateTime fechaCobro) {
}
