package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Códigos de descuento según catálogo FE v4.4 (Anexo 1).
@Getter
public enum CodigoDescuento {

    // 01
    REGALIA("01", "Descuento por regalía"),
    // 02
    REGALIA_IVA_COBRADO("02", "Descuento por regalía IVA cobrado al cliente"),
    // 03
    BONIFICACION("03", "Descuento por bonificación"),
    // 04
    VOLUMEN("04", "Descuento por volumen"),
    // 05
    TEMPORADA("05", "Descuento por temporada (estacional)"),
    // 06
    PROMOCIONAL("06", "Descuento promocional"),
    // 07
    COMERCIAL("07", "Descuento comercial"),
    // 08
    FRECUENCIA("08", "Descuento por frecuencia"),
    // 09
    SOSTENIDO("09", "Descuento sostenido"),
    // 99
    OTROS("99", "Otros descuentos");

    private final String codigo;
    private final String descripcion;

    CodigoDescuento(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static CodigoDescuento fromCodigo(String codigo) {
        for (CodigoDescuento item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de descuento inválido: " + codigo);
    }
}
