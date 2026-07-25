package cr.ac.fractall.facturacion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;

public record ExoneracionResponse(
        String tipoDocumentoEx1,
        String tipoDocumentoOtro,
        String numeroDocumento,
        Integer articulo,
        Integer inciso,
        String nombreInstitucion,
        String nombreInstitucionOtros,
        LocalDateTime fechaEmisionEx,
        BigDecimal tarifaExonerada,
        BigDecimal montoExoneracion) {

    public static ExoneracionResponse desde(ImpuestoLineaExoneracion entidad) {
        return new ExoneracionResponse(
                entidad.getTipoDocumentoEx1(),
                entidad.getTipoDocumentoOtro(),
                entidad.getNumeroDocumento(),
                entidad.getArticulo(),
                entidad.getInciso(),
                entidad.getNombreInstitucion(),
                entidad.getNombreInstitucionOtros(),
                entidad.getFechaEmisionEx(),
                entidad.getTarifaExonerada(),
                entidad.getMontoExoneracion());
    }
}
