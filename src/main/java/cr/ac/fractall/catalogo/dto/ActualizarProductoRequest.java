package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PATCH /catalogo/productos/{id}} -- actualización PARCIAL: un campo
 * {@code null} deja el valor actual intacto. {@code codigoCabys} solo dispara una nueva
 * validación contra Hacienda si cambia respecto al valor ya guardado (ver
 * {@code ProductoService#actualizar}).
 */
public record ActualizarProductoRequest(

        @Size(max = 50)
        String codigo,

        @Size(max = 255)
        String descripcion,

        @Size(max = 13)
        String codigoCabys,

        @Size(max = 20)
        String codigoUnidadFe,

        @DecimalMin(value = "0", message = "El precio de venta no puede ser negativo")
        BigDecimal precioVenta,

        Boolean activo) {
}
