package cr.ac.fractall.facturacion.dto;

import java.time.LocalDateTime;

import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;

public record ReferenciaResponse(
        int orden,
        String tipoDocIr,
        String tipoDocRefOtro,
        String numero,
        LocalDateTime fechaEmisionIr,
        String codigo,
        String codigoReferenciaOtro,
        String razon) {

    public static ReferenciaResponse desde(FacturaInformacionReferencia entidad) {
        return new ReferenciaResponse(
                entidad.getOrden(),
                entidad.getTipoDocIr(),
                entidad.getTipoDocRefOtro(),
                entidad.getNumero(),
                entidad.getFechaEmisionIr(),
                entidad.getCodigo(),
                entidad.getCodigoReferenciaOtro(),
                entidad.getRazon());
    }
}
