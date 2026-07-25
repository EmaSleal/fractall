package cr.ac.fractall.facturacion.dto;

import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;

public record CodigoComercialResponse(
        String tipo,
        String codigo) {

    public static CodigoComercialResponse desde(LineaCodigoComercial entidad) {
        return new CodigoComercialResponse(entidad.getTipo(), entidad.getCodigo());
    }
}
