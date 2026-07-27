package cr.ac.fractall.catalogo.dto;

import cr.ac.fractall.facturacion.fe.UnidadMedida;

public record UnidadMedidaResponse(String codigo, String descripcion) {

    public static UnidadMedidaResponse desde(UnidadMedida unidad) {
        return new UnidadMedidaResponse(unidad.getCodigo(), unidad.getDescripcion());
    }
}
