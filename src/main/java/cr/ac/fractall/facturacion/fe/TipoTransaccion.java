package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Tipos de transacción según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoTransaccion {

    // 01
    VENTA_NORMAL("01", "Venta normal de bienes y servicios"),
    // 02
    AUTOCONSUMO_EXENTO("02", "Mercancía de autoconsumo exento"),
    // 03
    AUTOCONSUMO_GRAVADO("03", "Mercancía de autoconsumo gravado"),
    // 04
    SERVICIO_AUTOCONSUMO_EXENTO("04", "Servicio de autoconsumo exento"),
    // 05
    SERVICIO_AUTOCONSUMO_GRAVADO("05", "Servicio de autoconsumo gravado"),
    // 06
    CUOTA_AFILIACION("06", "Cuota de afiliación"),
    // 07
    CUOTA_AFILIACION_EXENTA("07", "Cuota de afiliación exenta"),
    // 08
    BIENES_CAPITAL_EMISOR("08", "Bienes de capital para el emisor"),
    // 09
    BIENES_CAPITAL_RECEPTOR("09", "Bienes de capital para el receptor"),
    // 10
    BIENES_CAPITAL_AMBOS("10", "Bienes de capital para el emisor y el receptor"),
    // 11
    CAPITAL_AUTOCONSUMO_EXENTO("11", "Bienes de capital de autoconsumo exento para el emisor"),
    // 12
    CAPITAL_SIN_CONTRAPRESTACION("12", "Bienes de capital sin contraprestación a terceros exento para el emisor"),
    // 13
    SIN_CONTRAPRESTACION("13", "Sin contraprestación a terceros");

    private final String codigo;
    private final String descripcion;

    TipoTransaccion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoTransaccion fromCodigo(String codigo) {
        for (TipoTransaccion item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tipo de transacción inválido: " + codigo);
    }
}
