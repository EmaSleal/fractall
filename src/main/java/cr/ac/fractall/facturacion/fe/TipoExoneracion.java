package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Tipos de exoneración según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoExoneracion {

    // 01
    DGT("01", "Compras autorizadas por la Dirección General de Tributación"),
    // 02
    DIPLOMATICOS("02", "Ventas exentas a diplomáticos"),
    // 03
    LEY_ESPECIAL("03", "Autorizado por ley especial"),
    // 04
    DGH_LOCAL_GENERICA("04", "Exenciones Dirección General de Hacienda - autorización local genérica"),
    // 05
    DGH_TRANSITORIO_V("05", "Exenciones DGH - Transitorio V (ingeniería, arquitectura, topografía, obra civil)"),
    // 06
    TURISMO_ICT("06", "Servicios turísticos inscritos ante el ICT"),
    // 07
    TRANSITORIO_XVII_RECICLAJE("07", "Transitorio XVII (recolección, clasificación, almacenamiento de reciclaje y reutilizable)"),
    // 08
    ZONA_FRANCA("08", "Exoneración a zona franca"),
    // 09
    EXPORTACION_ART_11("09", "Exoneración de servicios complementarios para la exportación (Art. 11 RLIVA)"),
    // 10
    MUNICIPALIDADES("10", "Órganos de las corporaciones municipales"),
    // 11
    DGH_LOCAL_CONCRETA("11", "Exenciones DGH - autorización de impuesto local concreta"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoExoneracion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoExoneracion fromCodigo(String codigo) {
        for (TipoExoneracion item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tipo de exoneración inválido: " + codigo);
    }
}
