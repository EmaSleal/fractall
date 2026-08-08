package cr.ac.fractall.catalogo.dto;

import cr.ac.fractall.catalogo.modelo.Distrito;

public record DistritoResponse(String provinciaCodigo, String cantonCodigo, String codigo, String nombre) {

    public static DistritoResponse desde(Distrito distrito) {
        return new DistritoResponse(
                distrito.getId().getProvinciaCodigo(),
                distrito.getId().getCantonCodigo(),
                distrito.getId().getCodigo(),
                distrito.getNombre());
    }
}
