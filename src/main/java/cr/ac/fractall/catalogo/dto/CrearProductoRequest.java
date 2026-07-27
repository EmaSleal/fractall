package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /catalogo/productos} (sección 4.10 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>{@code descripcionCabys}, {@code porcentajeImpuesto}, {@code gravado} y
 * {@code cabysValidadoEn} NUNCA se aceptan como entrada del cliente -- se derivan siempre del
 * lado del servidor a partir de la validación contra la API de Hacienda (ver
 * {@code ProductoService#validarYObtenerCabys}).
 */
public record CrearProductoRequest(

        @Schema(description = "Internal product code (max 50 characters)", example = "PROD-001")
        @NotBlank
        @Size(max = 50)
        String codigo,

        @Schema(description = "Product description", example = "Servicio de consultoría")
        @NotBlank
        @Size(max = 255)
        String descripcion,

        @Schema(description = "CABYS code (13 digits, validated against Hacienda)", example = "9319902000000")
        @NotBlank
        @Size(max = 13)
        String codigoCabys,

        @Schema(description = "Unit of measure code as per Hacienda catalog (e.g. Sp=service, Unid=unit)", example = "Sp")
        @Size(max = 20)
        String codigoUnidadFe,

        @Schema(description = "Unit sale price (non-negative)", example = "1500.00")
        @NotNull
        @DecimalMin(value = "0", message = "El precio de venta no puede ser negativo")
        BigDecimal precioVenta,

        @Schema(description = "Whether the product is active (defaults to true)", example = "true")
        Boolean activo) {
}
