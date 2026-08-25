package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

/**
 * Perfil por tipo de comprobante electrónico (Release 2 / Fase B) -- fuente única de verdad para
 * el elemento raíz, namespace y XSD de cada uno de los 4 tipos de comprobante v4.4 de Hacienda
 * Costa Rica, consumida idénticamente por {@code XmlFacturaGeneratorServiceImpl} (generación) y
 * {@code XmlFacturaXsdValidator} (validación) -- ver el diseño de Fase B, decisión D-A.
 *
 * <p>Mismo patrón que {@link CodigoReferencia}/{@code TipoDocumentoIR}: enum con datos asociados
 * por constante en vez de un {@code @Component} con {@code Map<String,...>} -- {@code values()}
 * debe estar disponible ANTES de cualquier cableado de beans de Spring, porque
 * {@code XmlFacturaXsdValidator} compila los 4 esquemas en su propio constructor.
 *
 * <p>Las cardinalidades XSD crudas ({@code minOccurs}) NO son directamente los campos de este
 * enum cuando difieren de la política de negocio: {@link #receptorObligatorio} en particular es
 * la política de EMISIÓN (Receptor obligatorio en NC/ND por regla de negocio 6), no el
 * {@code minOccurs="0"} real que los 4 XSD declaran para {@code Receptor} -- codificar acá la
 * cardinalidad cruda permitiría emitir una NC sin receptor y aun así pasar la validación de
 * esquema.
 */
@Getter
public enum TipoComprobantePerfil {

    FACTURA_ELECTRONICA("01", "FacturaElectronica", "facturaElectronica",
            "xsd/FacturaElectronica_V4.4.xsd", true, true, true, true, 0,
            "Factura Electrónica", "Factura Electrónica Autorizada"),
    NOTA_DEBITO("02", "NotaDebitoElectronica", "notaDebitoElectronica",
            "xsd/NotaDebitoElectronica_V4.4.xsd", true, false, true, true, 1,
            "Nota de Débito Electrónica", "Nota de Débito Electrónica Autorizada"),
    NOTA_CREDITO("03", "NotaCreditoElectronica", "notaCreditoElectronica",
            "xsd/NotaCreditoElectronica_V4.4.xsd", true, false, true, true, 1,
            "Nota de Crédito Electrónica", "Nota de Crédito Electrónica Autorizada"),
    TIQUETE("04", "TiqueteElectronico", "tiqueteElectronico",
            "xsd/TiqueteElectronico_V4.4.xsd", false, true, false, false, 0,
            "Tiquete Electrónico", "Tiquete Electrónico Autorizado");

    private static final String NAMESPACE_BASE =
            "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/";

    private final String codigo;
    private final String elementoRaiz;
    private final String sufijoNamespace;
    private final String xsdClasspath;
    // Política de EMISIÓN, no minOccurs crudo -- ver el javadoc de la clase.
    private final boolean receptorObligatorio;
    private final boolean codigoActividadEmisorObligatorio;
    private final boolean codigoActividadReceptorSoportado;
    private final boolean tipoTransaccionSoportado;
    private final int referenciasMinimas;
    private final String nombreDocumento;
    private final String tituloAutorizado;

    TipoComprobantePerfil(String codigo, String elementoRaiz, String sufijoNamespace, String xsdClasspath,
            boolean receptorObligatorio, boolean codigoActividadEmisorObligatorio,
            boolean codigoActividadReceptorSoportado, boolean tipoTransaccionSoportado,
            int referenciasMinimas, String nombreDocumento, String tituloAutorizado) {
        this.codigo = codigo;
        this.elementoRaiz = elementoRaiz;
        this.sufijoNamespace = sufijoNamespace;
        this.xsdClasspath = xsdClasspath;
        this.receptorObligatorio = receptorObligatorio;
        this.codigoActividadEmisorObligatorio = codigoActividadEmisorObligatorio;
        this.codigoActividadReceptorSoportado = codigoActividadReceptorSoportado;
        this.tipoTransaccionSoportado = tipoTransaccionSoportado;
        this.referenciasMinimas = referenciasMinimas;
        this.nombreDocumento = nombreDocumento;
        this.tituloAutorizado = tituloAutorizado;
    }

    public String namespace() {
        return NAMESPACE_BASE + sufijoNamespace;
    }

    public String cierreRaiz() {
        return "</" + elementoRaiz + ">";
    }

    public static TipoComprobantePerfil fromCodigo(String codigo) {
        for (TipoComprobantePerfil item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Tipo de comprobante inválido: " + codigo);
    }
}
