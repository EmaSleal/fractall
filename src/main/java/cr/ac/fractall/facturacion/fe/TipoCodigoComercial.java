package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Tipos de código comercial según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoCodigoComercial {

    // 01
    CODIGO_VENDEDOR("01", "Código del producto del vendedor"),
    // 02
    CODIGO_COMPRADOR("02", "Código del producto del comprador"),
    // 03
    CODIGO_INDUSTRIA("03", "Código del producto asignado por la industria"),
    // 04
    USO_INTERNO("04", "Código de uso interno"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoCodigoComercial(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoCodigoComercial fromCodigo(String codigo) {
        for (TipoCodigoComercial item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tipo de código comercial inválido: " + codigo);
    }
}
