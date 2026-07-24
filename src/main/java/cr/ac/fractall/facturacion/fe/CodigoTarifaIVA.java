package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

import java.math.BigDecimal;

// Tarifas de IVA según catálogo FE v4.4 (Anexo 1).
@Getter
public enum CodigoTarifaIVA {

    // 01 - 0% Art. 32 num 1 RLIVA
    TARIFA_0_ARTICULO_32("01", "Tarifa 0% (Artículo 32, num 1, RLIVA)", new BigDecimal("0")),
    // 02 - 1%
    TARIFA_REDUCIDA_1("02", "Tarifa reducida 1%", new BigDecimal("1")),
    // 03 - 2%
    TARIFA_REDUCIDA_2("03", "Tarifa reducida 2%", new BigDecimal("2")),
    // 04 - 4%
    TARIFA_REDUCIDA_4("04", "Tarifa reducida 4%", new BigDecimal("4")),
    // 05 - Transitorio 0%
    TRANSITORIO_0("05", "Transitorio 0%", new BigDecimal("0")),
    // 06 - Transitorio 4%
    TRANSITORIO_4("06", "Transitorio 4%", new BigDecimal("4")),
    // 07 - Transitoria 8%
    TRANSITORIA_8("07", "Tarifa transitoria 8%", new BigDecimal("8")),
    // 08 - General 13%
    TARIFA_GENERAL_13("08", "Tarifa general 13%", new BigDecimal("13")),
    // 09 - 0.5%
    TARIFA_REDUCIDA_05("09", "Tarifa reducida 0.5%", new BigDecimal("0.5")),
    // 10 - Exenta
    TARIFA_EXENTA("10", "Tarifa exenta", new BigDecimal("0")),
    // 11 - 0% sin derecho a crédito
    TARIFA_0_SIN_CREDITO("11", "Tarifa 0% sin derecho a crédito", new BigDecimal("0"));

    private final String codigo;
    private final String descripcion;
    private final BigDecimal porcentaje;

    CodigoTarifaIVA(String codigo, String descripcion, BigDecimal porcentaje) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.porcentaje = porcentaje;
    }

    public static CodigoTarifaIVA fromCodigo(String codigo) {
        for (CodigoTarifaIVA item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tarifa IVA inválido: " + codigo);
    }
}
