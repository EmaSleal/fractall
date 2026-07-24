package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Códigos de impuesto según catálogo FE v4.4 (Anexo 1).
@Getter
public enum CodigoImpuesto {

    // 01
    IVA("01", "Impuesto al Valor Agregado"),
    // 02
    SELECTIVO_CONSUMO("02", "Impuesto Selectivo de Consumo"),
    // 03
    UNICO_COMBUSTIBLES("03", "Impuesto único a los combustibles"),
    // 04
    BEBIDAS_ALCOHOLICAS("04", "Impuesto específico de bebidas alcohólicas"),
    // 05
    BEBIDAS_NO_ALCOHOLICAS_JABONES("05", "Impuesto específico sobre bebidas envasadas sin contenido alcohólico y jabones de tocador"),
    // 06
    TABACO("06", "Impuesto a los productos de tabaco"),
    // 07
    IVA_CALCULO_ESPECIAL("07", "IVA (cálculo especial)"),
    // 08
    IVA_BIENES_USADOS_FACTOR("08", "IVA Régimen de Bienes Usados (Factor)"),
    // 12
    CEMENTO("12", "Impuesto específico al cemento"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    CodigoImpuesto(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static CodigoImpuesto fromCodigo(String codigo) {
        for (CodigoImpuesto item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de impuesto inválido: " + codigo);
    }
}
