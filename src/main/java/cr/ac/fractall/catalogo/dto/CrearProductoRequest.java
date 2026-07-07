package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;

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

        @NotBlank
        @Size(max = 50)
        String codigo,

        @NotBlank
        @Size(max = 255)
        String descripcion,

        @NotBlank
        @Size(max = 13)
        String codigoCabys,

        @Size(max = 20)
        String codigoUnidadFe,

        @NotNull
        @DecimalMin(value = "0", message = "El precio de venta no puede ser negativo")
        BigDecimal precioVenta,

        Boolean activo) {
}
