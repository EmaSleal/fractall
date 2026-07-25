package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;

import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;

public record OtrosCargoResponse(
        int orden,
        String tipoDocumentoOc,
        String tipoDocumentoOtros,
        String identidadTipo,
        String identidadNumero,
        String nombreTercero,
        String detalle,
        BigDecimal porcentajeOc,
        BigDecimal montoCargo) {

    public static OtrosCargoResponse desde(FacturaOtrosCargos entidad) {
        return new OtrosCargoResponse(
                entidad.getOrden(),
                entidad.getTipoDocumentoOc(),
                entidad.getTipoDocumentoOtros(),
                entidad.getIdentidadTipo(),
                entidad.getIdentidadNumero(),
                entidad.getNombreTercero(),
                entidad.getDetalle(),
                entidad.getPorcentajeOc(),
                entidad.getMontoCargo());
    }
}
