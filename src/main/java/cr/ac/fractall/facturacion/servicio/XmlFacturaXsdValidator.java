package cr.ac.fractall.facturacion.servicio;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

/**
 * Valida un XML de Factura Electrónica contra el XSD v4.4 oficial de Hacienda Costa Rica
 * (sub-tarea 3 de la Fase 8 -- ver el javadoc de {@link XmlFacturaGeneratorService}).
 *
 * <p>Portado (Categoría A, con recortes) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../facturacion/electronica/util/XmlValidator.java}
 * -- SOLO la validación contra el XSD de Factura Electrónica. Deliberadamente NO se portan:
 *
 * <ul>
 *   <li>El switch sobre {@code TipoComprobanteElectronico} y los otros 3 XSD (Tiquete/NotaCredito/
 *       NotaDebito) -- fuera de alcance del Release 1 (mismo límite ya documentado en
 *       {@code XmlFacturaGeneratorService}).
 *   <li>{@code formatearXml}, {@code extraerValorNodo}, {@code tieneFirmaDigital} -- ninguno se
 *       usa dentro de este alcance; {@code tieneFirmaDigital} en particular pertenece a la firma
 *       XAdES-BES, una fase futura separada. Ver también el placeholder de firma más abajo, que
 *       SÍ hizo falta introducir por esa misma frontera de alcance.
 *   <li>El fallback de "XSD no encontrado en classpath ni en filesystem -- degradar a solo
 *       bien-formado". En este proyecto el XSD SIEMPRE va empaquetado dentro del jar (paso 1 de
 *       esta sub-tarea); su ausencia en tiempo de ejecución es un bug de empaquetado, no un
 *       despliegue que todavía no descargó el archivo -- por eso {@link #cargarSchema()} falla
 *       ruidosamente (excepción en el constructor, falla el arranque del contexto Spring) en vez
 *       de degradar silenciosamente como el original.
 * </ul>
 *
 * <p><b>Gap real hallado en esta sub-tarea, no documentado en el original ni en su README:</b> el
 * XSD oficial de Hacienda importa {@code http://www.w3.org/2000/09/xmldsig#} (el elemento
 * {@code ds:Signature}, para la firma XML embebida) con
 * {@code schemaLocation="../../xmldsig-core-schema.xsd"} -- una ruta relativa pensada para el
 * layout del propio sitio de Hacienda ({@code cdn.comprobanteselectronicos.go.cr}), no para un
 * archivo bundleado localmente; sin una resolución explícita, {@link SchemaFactory#newSchema}
 * falla al compilar con {@code SAXParseException: Cannot resolve the name 'ds:Signature'}. El
 * propio README del proyecto de referencia (`xsd/README.md`) marca el esquema auxiliar
 * ({@code xmldsig-core-schema_V1.1.xsd}) como "opcional" y nunca lo bundlea -- es decir, el
 * {@code XmlValidator} original probablemente nunca compiló el {@code Schema} exitosamente en la
 * práctica (nadie lo cubre en {@code XmlGeneratorServiceImplTest} tampoco). Acá se resuelve de
 * verdad: se bundlea una copia local del schema W3C estándar
 * ({@code src/main/resources/xsd/xmldsig-core-schema.xsd}, descargado de
 * {@code https://www.w3.org/TR/xmldsig-core/xmldsig-core-schema.xsd}) y se registra un
 * {@link LSResourceResolver} en el {@link SchemaFactory} que intercepta ese import por namespace
 * y lo resuelve desde el classpath, sin tocar red en tiempo de ejecución.
 *
 * <p><b>Hallazgo arquitectónico mayor de esta sub-tarea -- {@code ds:Signature} es OBLIGATORIO en
 * el XSD real, no opcional:</b> {@code FacturaElectronicaType} termina su secuencia con
 * {@code <xs:element ref="ds:Signature" minOccurs="1" maxOccurs="5"/>} -- Hacienda exige que TODO
 * comprobante que se valide contra este XSD venga ya envuelto en una firma XML enveloped
 * (XAdES-BES). Pero {@code XmlFacturaGeneratorServiceImpl} genera XML SIN firmar por diseño (la
 * firma es la sub-tarea 4, todavía no construida) -- así que un XML con datos perfectamente
 * correctos NUNCA puede pasar una validación estricta contra el XSD oficial tal cual, sin importar
 * qué tan bien esté escrito el generador. (El {@code XmlValidator} original nunca topó con esto
 * porque, como se documentó arriba, su único call site real usa {@code validarXml} -- solo buena
 * formación -- y {@code validarContraXsd} es código muerto que jamás se invoca desde
 * {@code XmlGeneratorServiceImpl}; el original jamás validó contra el XSD de verdad en producción.)
 *
 * <p>Para poder validar de verdad todo el CONTENIDO de negocio del XML (tipos, cardinalidades,
 * catálogos cerrados, longitudes -- todo lo que sí es responsabilidad de esta sub-tarea) sin
 * bloquear en un requisito que pertenece a una fase posterior todavía no construida,
 * {@link #validar(String)} inserta un {@code <ds:Signature>} PLACEHOLDER -- estructuralmente
 * válido contra {@code xmldsig-core-schema.xsd} (con valores base64 de relleno, sin ningún
 * significado criptográfico) -- justo antes de {@code </FacturaElectronica>} en una COPIA del XML
 * generada en memoria SOLO para satisfacer la cardinalidad {@code minOccurs="1"} durante esta
 * validación, nunca en el XML que el método realmente recibe ni en lo que
 * {@code XmlFacturaGeneratorServiceImpl} termina devolviendo al llamador. Esto es intencional y
 * está acotado a esta clase: no es firma digital (no hay clave, no hay canonicalización real, no
 * hay hash real del documento) y no debe confundirse con la sub-tarea 4. Cuando esa sub-tarea
 * construya la firma real, la validación debería recablearse para correr DESPUÉS de firmar, sobre
 * el XML ya firmado de verdad -- momento en el que este placeholder ({@code FIRMA_PLACEHOLDER},
 * {@code insertarFirmaPlaceholder}) debería eliminarse por completo.
 *
 * <p><b>Desviación deliberada frente al original:</b> el original compila el {@link Schema} EN
 * CADA llamada a {@code validarContraXsd}. Compilar un {@code Schema} de ~120KB via
 * {@link SchemaFactory#newSchema} parsea y valida la gramática completa del XSD (incluyendo sus
 * imports/includes transitivos) -- trabajo que no cambia entre llamadas porque el contenido del
 * XSD es estático en tiempo de compilación del jar. Acá se compila UNA sola vez en el
 * constructor y se cachea como campo de instancia: {@link Schema} es inmutable y thread-safe una
 * vez compilado (así lo documenta la propia Javadoc de {@code javax.xml.validation}), así que
 * cachearlo en un bean {@code @Component} (singleton por defecto en Spring) es seguro. Lo que NO
 * se cachea es el {@link Validator} -- ese sí es stateful y no thread-safe, por eso
 * {@link #validar(String)} crea uno nuevo con {@link Schema#newValidator()} en cada llamada,
 * igual que el original.
 */
@Component
public class XmlFacturaXsdValidator {

    private static final String XSD_CLASSPATH = "xsd/FacturaElectronica_V4.4.xsd";
    private static final String XMLDSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XMLDSIG_CLASSPATH = "xsd/xmldsig-core-schema.xsd";

    private static final String CIERRE_RAIZ = "</FacturaElectronica>";

    /**
     * Placeholder de {@code <ds:Signature>} usado SOLO dentro de {@link #validar(String)} -- ver
     * el javadoc de la clase ("Hallazgo arquitectónico mayor..."). Estructuralmente válido contra
     * {@code xmldsig-core-schema.xsd} (todos los elementos obligatorios de {@code SignatureType}/
     * {@code SignedInfoType}/{@code ReferenceType} presentes, con {@code Algorithm} apuntando a
     * URIs reales del estándar XMLDSig) pero con valores base64 de relleno sin ningún significado
     * criptográfico -- nunca se firma nada de verdad acá.
     */
    private static final String FIRMA_PLACEHOLDER =
            "<ds:Signature xmlns:ds=\"" + XMLDSIG_NAMESPACE + "\">"
                    + "<ds:SignedInfo>"
                    + "<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\"/>"
                    + "<ds:SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha256\"/>"
                    + "<ds:Reference URI=\"\">"
                    + "<ds:DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
                    + "<ds:DigestValue>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</ds:DigestValue>"
                    + "</ds:Reference>"
                    + "</ds:SignedInfo>"
                    + "<ds:SignatureValue>"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
                    + "</ds:SignatureValue>"
                    + "</ds:Signature>";

    private final Schema schema;

    public XmlFacturaXsdValidator() {
        this.schema = cargarSchema();
    }

    private Schema cargarSchema() {
        ClassPathResource recurso = new ClassPathResource(XSD_CLASSPATH);
        if (!recurso.exists()) {
            // Bug de empaquetado (XSD no incluido en resources), no un caso a degradar -- ver el
            // javadoc de la clase.
            throw new IllegalStateException(
                    "XSD de Factura Electrónica no encontrado en el classpath: " + XSD_CLASSPATH);
        }
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            // Resuelve el import a xmldsig-core-schema.xsd desde el classpath -- ver el javadoc
            // de la clase sobre por qué la schemaLocation relativa del XSD oficial no sirve acá.
            schemaFactory.setResourceResolver(crearResolvedorXmldsig());
            return schemaFactory.newSchema(new StreamSource(recurso.getInputStream()));
        } catch (IOException | SAXException e) {
            throw new IllegalStateException(
                    "No se pudo compilar el XSD de Factura Electrónica: " + XSD_CLASSPATH, e);
        }
    }

    private LSResourceResolver crearResolvedorXmldsig() {
        return (type, namespaceURI, publicId, systemId, baseURI) -> {
            boolean esImportXmldsig = XMLDSIG_NAMESPACE.equals(namespaceURI)
                    || (systemId != null && systemId.endsWith("xmldsig-core-schema.xsd"));
            if (!esImportXmldsig) {
                // Ningún otro import se espera en este XSD -- si apareciera uno, dejar que el
                // resolvedor por defecto de Xerces lo intente (probablemente falle igual, pero de
                // forma diagnosticable) en vez de silenciarlo acá.
                return null;
            }
            try {
                InputStream contenido = new ClassPathResource(XMLDSIG_CLASSPATH).getInputStream();
                ClasspathLSInput entrada = new ClasspathLSInput(contenido);
                entrada.setSystemId(XMLDSIG_CLASSPATH);
                return entrada;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "No se pudo cargar el esquema auxiliar xmldsig-core-schema.xsd desde el classpath: "
                                + XMLDSIG_CLASSPATH, e);
            }
        };
    }

    /**
     * Valida el XML contra el XSD de Factura Electrónica v4.4.
     *
     * <p>Internamente valida una COPIA del XML con {@link #FIRMA_PLACEHOLDER} insertado -- ver el
     * javadoc de la clase ("Hallazgo arquitectónico mayor..."). El {@code xml} recibido nunca se
     * modifica ni se devuelve; el llamador sigue trabajando con el XML sin firmar original.
     *
     * @param xml el XML ya generado (con declaración {@code <?xml ...?>} y namespace) a validar,
     *     sin firmar
     * @throws XmlFacturaInvalidoException si el XML no cumple el esquema -- el mensaje incluye el
     *     detalle de la regla del XSD que falló (via {@link SAXException#getMessage()}), útil
     *     para diagnosticar rechazos de Hacienda más adelante.
     */
    public void validar(String xml) {
        String xmlParaEsquema = insertarFirmaPlaceholder(xml);
        try {
            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xmlParaEsquema)));
        } catch (SAXException e) {
            throw new XmlFacturaInvalidoException(e.getMessage(), e);
        } catch (IOException e) {
            // StreamSource sobre StringReader no lanza IOException en la práctica, pero
            // Validator#validate lo declara como checked -- se envuelve igual por completitud.
            throw new XmlFacturaInvalidoException("Error de I/O validando el XML generado: " + e.getMessage(), e);
        }
    }

    /**
     * Inserta {@link #FIRMA_PLACEHOLDER} justo antes del cierre de {@code <FacturaElectronica>}.
     * Si el cierre esperado no aparece (documento con otra forma, ya inválido de por sí), se
     * valida el XML tal cual -- el propio XSD reportará el problema real, más útil que enmascararlo.
     */
    private String insertarFirmaPlaceholder(String xml) {
        int indiceCierre = xml.lastIndexOf(CIERRE_RAIZ);
        if (indiceCierre < 0) {
            return xml;
        }
        return xml.substring(0, indiceCierre) + FIRMA_PLACEHOLDER + xml.substring(indiceCierre);
    }

    /**
     * Implementación mínima de {@link LSInput} solo para exponer un {@link InputStream} del
     * classpath a {@link LSResourceResolver#resolveResource} -- Xerces únicamente necesita
     * {@code getByteStream()}/{@code getSystemId()} en este uso, el resto de los métodos del
     * contrato (character stream, string data, public id, base URI, encoding, certified text) no
     * aplican para un recurso binario ya resuelto y quedan como no-ops.
     */
    private static final class ClasspathLSInput implements LSInput {

        private final InputStream byteStream;
        private String systemId;

        private ClasspathLSInput(InputStream byteStream) {
            this.byteStream = byteStream;
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
            // No aplica -- ver el javadoc de la clase.
        }

        @Override
        public InputStream getByteStream() {
            return byteStream;
        }

        @Override
        public void setByteStream(InputStream byteStream) {
            // No aplica -- el stream se fija en el constructor.
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {
            // No aplica -- ver el javadoc de la clase.
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        @Override
        public String getPublicId() {
            return null;
        }

        @Override
        public void setPublicId(String publicId) {
            // No aplica -- ver el javadoc de la clase.
        }

        @Override
        public String getBaseURI() {
            return null;
        }

        @Override
        public void setBaseURI(String baseURI) {
            // No aplica -- ver el javadoc de la clase.
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(String encoding) {
            // No aplica -- ver el javadoc de la clase.
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
            // No aplica -- ver el javadoc de la clase.
        }
    }
}
