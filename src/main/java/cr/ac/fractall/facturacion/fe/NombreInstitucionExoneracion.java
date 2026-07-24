package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Instituciones emisoras de exoneración según catálogo FE v4.4 (Anexo 1).
@Getter
public enum NombreInstitucionExoneracion {

    // 01
    HACIENDA("01", "Ministerio de Hacienda"),
    // 02
    RELACIONES_EXTERIORES("02", "Ministerio de Relaciones Exteriores y Culto"),
    // 03
    AGRICULTURA("03", "Ministerio de Agricultura y Ganadería"),
    // 04
    MEIC("04", "Ministerio de Economía, Industria y Comercio"),
    // 05
    CRUZ_ROJA("05", "Cruz Roja Costarricense"),
    // 06
    BOMBEROS("06", "Benemérito Cuerpo de Bomberos de Costa Rica"),
    // 07
    ESPIRITU_SANTO("07", "Asociación Obras del Espíritu Santo"),
    // 08
    FECRUNAPA("08", "Federación Cruzada Nacional de Protección al Anciano (FECRUNAPA)"),
    // 09
    EARTH("09", "Escuela de Agricultura de la Región Húmeda (EARTH)"),
    // 10
    INCAE("10", "Instituto Centroamericano de Administración de Empresas (INCAE)"),
    // 11
    JPS("11", "Junta de Protección Social (JPS)"),
    // 12
    ARESEP("12", "Autoridad Reguladora de los Servicios Públicos (ARESEP)"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    NombreInstitucionExoneracion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static NombreInstitucionExoneracion fromCodigo(String codigo) {
        for (NombreInstitucionExoneracion item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de institución de exoneración inválido: " + codigo);
    }
}
