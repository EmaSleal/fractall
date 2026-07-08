package cr.ac.fractall.facturacion.servicio;

/**
 * La carga del certificado {@code .p12} de la empresa (descifrado envelope-encryption, Fase 5) o
 * la firma XAdES-BES en sí ({@code javax.xml.crypto.dsig}, JSR 105) fallaron -- ver el javadoc de
 * {@link XmlFacturaFirmaService}.
 *
 * <p>Cubre tanto problemas de certificado (empresa sin {@code .p12} cargado, PIN ausente en Vault,
 * keystore/PIN inválidos, clave privada o certificado no resolubles) como fallas del propio proceso
 * de firma (parseo del XML, construcción del bloque {@code xades:QualifyingProperties},
 * canonicalización, cómputo de la firma) -- mismo principio que {@link XmlFacturaInvalidoException}:
 * un tipo dedicado en vez de dejar escapar {@code GeneralSecurityException}/
 * {@code XMLSignatureException} crudas, para que el llamador HTTP vea un error de dominio
 * diagnosticable en vez de una excepción de bajo nivel de JSR 105.
 */
public class XmlFacturaFirmaException extends RuntimeException {

    public XmlFacturaFirmaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
