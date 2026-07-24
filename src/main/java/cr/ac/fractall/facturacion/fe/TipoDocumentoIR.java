package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Tipos de documento para InformacionReferencia según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoDocumentoIR {

    // 01
    FACTURA_ELECTRONICA("01", "Factura electrónica"),
    // 02
    NOTA_DEBITO_ELECTRONICA("02", "Nota de débito electrónica"),
    // 03
    NOTA_CREDITO_ELECTRONICA("03", "Nota de crédito electrónica"),
    // 04
    TIQUETE_ELECTRONICO("04", "Tiquete electrónico"),
    // 05
    NOTA_DESPACHO("05", "Nota de despacho"),
    // 06
    CONTRATO("06", "Contrato"),
    // 07
    PROCEDIMIENTO("07", "Procedimiento"),
    // 08
    CONTINGENCIA("08", "Comprobante emitido en contingencia"),
    // 09
    DEVOLUCION_MERCADERIA("09", "Devolución de mercadería"),
    // 10
    RECHAZADO_HACIENDA("10", "Comprobante electrónico rechazado por el Ministerio de Hacienda"),
    // 11
    SUSTITUYE_RECHAZADA_RECEPTOR("11", "Sustituye factura rechazada por el receptor del comprobante"),
    // 12
    SUSTITUYE_EXPORTACION("12", "Sustituye factura de exportación"),
    // 13
    FACTURACION_MES_VENCIDO("13", "Facturación mes vencido"),
    // 14
    REGIMEN_ESPECIAL("14", "Comprobante aportado por contribuyente de régimen especial"),
    // 15
    SUSTITUYE_FACTURA_COMPRA("15", "Sustituye una factura electrónica de compra"),
    // 16
    PROVEEDOR_NO_DOMICILIADO("16", "Comprobante de proveedor no domiciliado"),
    // 17
    NOTA_CREDITO_FACTURA_COMPRA("17", "Nota de crédito a factura electrónica de compra"),
    // 18
    NOTA_DEBITO_FACTURA_COMPRA("18", "Nota de débito a factura electrónica de compra"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoDocumentoIR(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoDocumentoIR fromCodigo(String codigo) {
        for (TipoDocumentoIR item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de referencia inválido: " + codigo);
    }
}
