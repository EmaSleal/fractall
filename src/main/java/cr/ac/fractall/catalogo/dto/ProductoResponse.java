package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.catalogo.Producto;

public record ProductoResponse(
        UUID id,
        String codigo,
        String descripcion,
        String codigoCabys,
        String descripcionCabys,
        LocalDateTime cabysValidadoEn,
        String codigoUnidadFe,
        BigDecimal precioVenta,
        boolean gravado,
        BigDecimal porcentajeImpuesto,
        boolean activo) {

    public static ProductoResponse desde(Producto producto) {
        return new ProductoResponse(
                producto.getId(),
                producto.getCodigo(),
                producto.getDescripcion(),
                producto.getCodigoCabys(),
                producto.getDescripcionCabys(),
                producto.getCabysValidadoEn(),
                producto.getCodigoUnidadFe(),
                producto.getPrecioVenta(),
                producto.isGravado(),
                producto.getPorcentajeImpuesto(),
                producto.isActivo());
    }
}
