package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.modelo.LineaDescuento;

public record DescuentoResponse(
        BigDecimal montoDescuento,
        String codigoDescuento,
        String codigoDescuentoOtro,
        String naturalezaDescuento) {

    public static DescuentoResponse desde(LineaDescuento entidad) {
        return new DescuentoResponse(
                entidad.getMontoDescuento(),
                entidad.getCodigoDescuento(),
                entidad.getCodigoDescuentoOtro(),
                entidad.getNaturalezaDescuento());
    }
}
