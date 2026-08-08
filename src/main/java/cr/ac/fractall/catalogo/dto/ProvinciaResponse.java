package cr.ac.fractall.catalogo.dto;

import cr.ac.fractall.catalogo.modelo.Provincia;

public record ProvinciaResponse(String codigo, String nombre) {

    public static ProvinciaResponse desde(Provincia provincia) {
        return new ProvinciaResponse(provincia.getCodigo(), provincia.getNombre());
    }
}
