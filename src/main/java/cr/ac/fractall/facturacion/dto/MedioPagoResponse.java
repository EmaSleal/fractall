package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;

public record MedioPagoResponse(
        int orden,
        String tipoMedioPago,
        String medioPagoOtros,
        BigDecimal totalMedioPago) {

    public static MedioPagoResponse desde(FacturaMedioPago entidad) {
        return new MedioPagoResponse(
                entidad.getOrden(),
                entidad.getTipoMedioPago(),
                entidad.getMedioPagoOtros(),
                entidad.getTotalMedioPago());
    }
}
