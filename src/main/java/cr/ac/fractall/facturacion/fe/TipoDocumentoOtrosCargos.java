package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Tipos de documento para OtrosCargos según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoDocumentoOtrosCargos {

    // 01
    PARAFISCAL("01", "Contribución parafiscal"),
    // 02
    TIMBRE_CRUZ_ROJA("02", "Timbre de la Cruz Roja"),
    // 03
    TIMBRE_BOMBEROS("03", "Timbre del Benemérito Cuerpo de Bomberos de Costa Rica"),
    // 04
    COBRO_TERCERO("04", "Cobro de un tercero"),
    // 05
    COSTOS_EXPORTACION("05", "Costos de exportación"),
    // 06
    IMPUESTO_SERVICIO_10("06", "Impuesto de servicio 10%"),
    // 07
    TIMBRE_COLEGIOS("07", "Timbre de colegios profesionales"),
    // 08
    DEPOSITO_GARANTIA("08", "Depósitos de garantía"),
    // 09
    MULTA_PENALIZACION("09", "Multas o penalizaciones"),
    // 10
    INTERES_MORATORIO("10", "Intereses moratorios"),
    // 99
    OTROS("99", "Otros cargos");

    private final String codigo;
    private final String descripcion;

    TipoDocumentoOtrosCargos(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoDocumentoOtrosCargos fromCodigo(String codigo) {
        for (TipoDocumentoOtrosCargos item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tipo de otros cargos inválido: " + codigo);
    }
}
