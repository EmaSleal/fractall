package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Condición de venta según catálogo FE v4.4 (Anexo 1).
@Getter
public enum CondicionVenta {

    // 01
    CONTADO("01", "Contado"),
    // 02
    CREDITO("02", "Crédito"),
    // 03
    CONSIGNACION("03", "Consignación"),
    // 04
    APARTADO("04", "Apartado"),
    // 05
    ARRENDAMIENTO_OPCION_COMPRA("05", "Arrendamiento con opción de compra"),
    // 06
    ARRENDAMIENTO_FINANCIERO_FUNCION("06", "Arrendamiento en función financiera"),
    // 07
    COBRO_FAVOR_TERCERO("07", "Cobro a favor de un tercero"),
    // 08
    SERVICIOS_ESTADO_CREDITO("08", "Servicios prestados al Estado a crédito"),
    // 10
    VENTA_CREDITO_IVA_90_DIAS("10", "Venta a crédito en IVA hasta 90 días (Art. 27 LIVA)"),
    // 12
    VENTA_MERCANCIA_NO_NACIONALIZADA("12", "Venta mercancía no nacionalizada"),
    // 13
    VENTA_BIENES_USADOS("13", "Venta bienes usados no contribuyente"),
    // 14
    ARRENDAMIENTO_OPERATIVO("14", "Arrendamiento operativo"),
    // 15
    ARRENDAMIENTO_FINANCIERO("15", "Arrendamiento financiero"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    CondicionVenta(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static CondicionVenta fromCodigo(String codigo) {
        for (CondicionVenta item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de condición de venta inválido: " + codigo);
    }
}
