package cr.ac.fractall.facturacion.servicio;

import java.io.IOException;
import java.io.StringReader;
import java.util.EnumMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;

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
 * {@code https://www.w3.org/TR/xmldsig-core/xmldsig-core-schema.xsd}) y se compila JUNTO con el
 * XSD oficial en una sola llamada a {@link SchemaFactory#newSchema(Source[])} -- ver
 * {@link #cargarEsquema(TipoComprobantePerfil)} para por qué esto reemplazó a un
 * {@code LSResourceResolver} custom (intentado primero, descartado por inestable).
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
 * {@link #validar(String, TipoComprobantePerfil)} crea uno nuevo con {@link Schema#newValidator()}
 * en cada llamada, igual que el original.
 *
 * <p><b>Release 2 / Fase B -- parametrizado por {@link TipoComprobantePerfil}:</b> lo descrito
 * arriba, escrito para el Release 1 (solo Factura Electrónica), sigue siendo válido en su
 * totalidad; lo único que cambia es que ahora se compilan y cachean los 4 esquemas v4.4 (uno por
 * {@link TipoComprobantePerfil}), en un {@link EnumMap} en vez de un único {@link Schema}. El
 * método de un solo argumento ({@code validar(String)}) se elimina por completo -- el único
 * llamador real siempre tiene un perfil disponible (lo deriva del {@code ComprobanteElectronico}
 * ya cargado), y mantener ambas firmas reintroduciría la trampa de sobrecarga ambigua ya
 * documentada como ADR-1 en {@code ComprobanteXmlPersistenceService}.
 */
@Component
public class XmlFacturaXsdValidator {

    private static final String XMLDSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XMLDSIG_CLASSPATH = "xsd/xmldsig-core-schema.xsd";

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

    // EnumMap en vez de Map<String, Schema> -- clave total por construcción (una entrada por
    // cada TipoComprobantePerfil, sin posibilidad de un mapa parcialmente poblado en runtime).
    // Ver el javadoc de la clase, sección "Release 2 / Fase B".
    private final Map<TipoComprobantePerfil, Schema> esquemas;

    public XmlFacturaXsdValidator() {
        this.esquemas = cargarEsquemas();
    }

    private Map<TipoComprobantePerfil, Schema> cargarEsquemas() {
        Map<TipoComprobantePerfil, Schema> resultado = new EnumMap<>(TipoComprobantePerfil.class);
        for (TipoComprobantePerfil perfil : TipoComprobantePerfil.values()) {
            resultado.put(perfil, cargarEsquema(perfil));
        }
        return resultado;
    }

    /**
     * Compila {@code xmldsig-core-schema.xsd} y el XSD oficial de {@code perfil} JUNTOS, en una
     * sola llamada a {@link SchemaFactory#newSchema(Source[])} -- reemplaza dos intentos previos
     * que resultaron inestables en CI (nunca en local, sí ahí, con distintos XSD fallando en
     * corridas distintas): primero una única {@code SchemaFactory} reutilizada entre los 4
     * {@code newSchema()} del loop, después una {@code SchemaFactory} nueva por XSD pero con un
     * {@code LSResourceResolver} custom resolviendo el import en tiempo de ejecución. Ninguna de
     * las dos hipótesis (estado filtrándose entre compilaciones, stream de un solo uso mal
     * reabierto) resolvió el problema de fondo: el patrón "{@code LSResourceResolver} que
     * intercepta un {@code <xs:import>}" tiene comportamiento no completamente determinístico
     * documentado contra la implementación de Xerces embebida en el JDK cuando se ejecuta
     * repetidamente en la misma JVM (el primer XSD del loop, {@code FACTURA_ELECTRONICA}, NUNCA
     * fallaba -- solo los que se compilaban después). Pasar ambos XSD como un {@code Source[]} le
     * entrega a JAXP el set completo de antemano, sin ningún callback en medio: es el patrón
     * estándar de la API para "un schema que importa un componente de otro schema que ya tenés en
     * disco", y no depende de temporalidad ni de cuántas veces se invoque un resolver.
     */
    private Schema cargarEsquema(TipoComprobantePerfil perfil) {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        String xsdClasspath = perfil.getXsdClasspath();
        ClassPathResource recursoXsd = new ClassPathResource(xsdClasspath);
        ClassPathResource recursoXmldsig = new ClassPathResource(XMLDSIG_CLASSPATH);
        if (!recursoXsd.exists()) {
            // Bug de empaquetado (XSD no incluido en resources), no un caso a degradar -- ver el
            // javadoc de la clase.
            throw new IllegalStateException(
                    "XSD de " + perfil + " no encontrado en el classpath: " + xsdClasspath);
        }
        try {
            Source[] fuentes = {
                    new StreamSource(recursoXmldsig.getInputStream(), XMLDSIG_CLASSPATH),
                    new StreamSource(recursoXsd.getInputStream(), xsdClasspath)
            };
            return schemaFactory.newSchema(fuentes);
        } catch (IOException | SAXException e) {
            throw new IllegalStateException(
                    "No se pudo compilar el XSD de " + perfil + ": " + xsdClasspath, e);
        }
    }

    /**
     * Valida el XML contra el XSD v4.4 correspondiente a {@code perfil}.
     *
     * <p>Internamente valida una COPIA del XML con {@link #FIRMA_PLACEHOLDER} insertado -- ver el
     * javadoc de la clase ("Hallazgo arquitectónico mayor..."). El {@code xml} recibido nunca se
     * modifica ni se devuelve; el llamador sigue trabajando con el XML sin firmar original.
     *
     * @param xml el XML ya generado (con declaración {@code <?xml ...?>} y namespace) a validar,
     *     sin firmar
     * @param perfil el {@link TipoComprobantePerfil} que determina QUÉ esquema del
     *     {@link #esquemas EnumMap} se usa -- si {@code xml} fue generado para un tipo de
     *     comprobante distinto al de {@code perfil}, la validación DEBE fallar por mismatch de
     *     elemento raíz/namespace, nunca pasar silenciosamente (ver
     *     {@code XmlFacturaXsdValidatorProfileTest}).
     * @throws XmlFacturaInvalidoException si el XML no cumple el esquema -- el mensaje incluye el
     *     detalle de la regla del XSD que falló (via {@link SAXException#getMessage()}), útil
     *     para diagnosticar rechazos de Hacienda más adelante.
     */
    public void validar(String xml, TipoComprobantePerfil perfil) {
        String xmlParaEsquema = insertarFirmaPlaceholder(xml, perfil);
        try {
            Validator validator = esquemas.get(perfil).newValidator();
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
     * Inserta {@link #FIRMA_PLACEHOLDER} justo antes del cierre de {@code perfil.cierreRaiz()}.
     * Si el cierre esperado no aparece (documento con otra forma, ya inválido de por sí -- o
     * generado para un perfil distinto, ver el javadoc de {@link #validar}), se valida el XML tal
     * cual -- el propio XSD reportará el problema real, más útil que enmascararlo.
     */
    private String insertarFirmaPlaceholder(String xml, TipoComprobantePerfil perfil) {
        int indiceCierre = xml.lastIndexOf(perfil.cierreRaiz());
        if (indiceCierre < 0) {
            return xml;
        }
        return xml.substring(0, indiceCierre) + FIRMA_PLACEHOLDER + xml.substring(indiceCierre);
    }
}
