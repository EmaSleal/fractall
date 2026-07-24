package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Códigos de referencia según catálogo FE v4.4 (Anexo 1).
@Getter
public enum CodigoReferencia {

    // 01
    ANULA_DOCUMENTO("01", "Anula documento de referencia"),
    // 02
    CORRIGE_TEXTO("02", "Corrige texto de documento de referencia"),
    // 04
    REFERENCIA_OTRO("04", "Referencia a otro documento"),
    // 05
    SUSTITUYE_CONTINGENCIA("05", "Sustituye comprobante provisional por contingencia"),
    // 06
    DEVOLUCION_MERCANCIA("06", "Devolución de mercancía"),
    // 07
    SUSTITUYE_ELECTRONICO("07", "Sustituye comprobante electrónico"),
    // 08
    FACTURA_ENDOSADA("08", "Factura endosada"),
    // 09
    NOTA_CREDITO_FINANCIERA("09", "Nota de crédito financiera"),
    // 10
    NOTA_DEBITO_FINANCIERA("10", "Nota de débito financiera"),
    // 11
    PROVEEDOR_NO_DOMICILIADO("11", "Proveedor no domiciliado"),
    // 12
    CREDITO_EXONERACION("12", "Crédito por exoneración posterior a la facturación"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    CodigoReferencia(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static CodigoReferencia fromCodigo(String codigo) {
        for (CodigoReferencia item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de referencia inválido: " + codigo);
    }
}
