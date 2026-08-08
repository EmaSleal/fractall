package cr.ac.fractall.catalogo.dto;

import cr.ac.fractall.catalogo.modelo.Canton;

public record CantonResponse(String provinciaCodigo, String codigo, String nombre) {

    public static CantonResponse desde(Canton canton) {
        return new CantonResponse(canton.getId().getProvinciaCodigo(), canton.getId().getCodigo(), canton.getNombre());
    }
}
