package cr.ac.fractall.facturacion.servicio;

/**
 * El XML de Factura Electrónica que {@link XmlFacturaGeneratorService#generarXmlFactura}
 * construyó no cumple el XSD v4.4 oficial de Hacienda ({@link XmlFacturaXsdValidator}).
 *
 * <p>Esto SIEMPRE es un bug interno (un dato mal mapeado, un elemento faltante u ordenado mal en
 * el generador) y nunca una entrada de usuario inválida -- para cuando este método corre, la
 * {@code Factura} y el {@code ComprobanteElectronico} ya pasaron toda la validación de negocio de
 * {@code FacturaService#crear}. Mismo principio que las demás invariantes internas de
 * {@code XmlFacturaGeneratorServiceImpl} (FKs rotas, fecha ausente), que se señalan con
 * {@code IllegalStateException} -- se usa un tipo dedicado en vez de eso, en cambio, porque acá
 * el detalle de la violación de esquema (qué regla del XSD falló) es información operativa
 * valiosa para diagnosticar por qué Hacienda rechazaría el comprobante, y un tipo propio permite
 * capturarlo de forma específica más adelante si hace falta (p. ej. reintentos o alertas), sin
 * confundirlo con cualquier otro {@code IllegalStateException} genérico del generador.
 */
public class XmlFacturaInvalidoException extends RuntimeException {

    public XmlFacturaInvalidoException(String detalleViolacionXsd, Throwable causa) {
        super("XML de factura electrónica generado no es válido contra el XSD v4.4 de Hacienda: "
                + detalleViolacionXsd, causa);
    }
}
