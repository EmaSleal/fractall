package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;

/**
 * Fase B, tarea 1.1: confirma que {@link TipoComprobantePerfil} (namespace + elemento raíz) NO
 * hace drift contra el contenido real de los 4 XSD v4.4 bundleados, y que ese drift jamás pasa
 * silenciosamente cuando ocurre a nivel de validación (spec de Fase B, dominio
 * {@code xml-comprobante-generacion}, escenario "Drift between generator output and validator
 * target is caught, not silently passed").
 *
 * <p>No usa contexto de Spring -- {@link XmlFacturaXsdValidator} no depende de ningún bean.
 */
class XmlFacturaXsdValidatorProfileTest {

    @Test
    void cadaPerfilCoincideConElTargetNamespaceYElementoRaizDeSuXsdBundleado() throws IOException {
        for (TipoComprobantePerfil perfil : TipoComprobantePerfil.values()) {
            String contenidoXsd = leerClasspath(perfil.getXsdClasspath());

            assertThat(contenidoXsd)
                    .as("targetNamespace del XSD de %s", perfil)
                    .contains("targetNamespace=\"" + perfil.namespace() + "\"");
            assertThat(contenidoXsd)
                    .as("elemento raíz del XSD de %s", perfil)
                    .contains("<xs:element name=\"" + perfil.getElementoRaiz() + "\">");
        }
    }

    /**
     * Escenario de drift del spec: un defecto hipotético del generador emite contenido de Nota de
     * Crédito (root {@code <NotaCreditoElectronica>}) pero la validación se pide contra el perfil
     * de Factura Electrónica -- root/namespace jamás calzan contra
     * {@code FacturaElectronica_V4.4.xsd}, así que la validación DEBE fallar, nunca pasar
     * silenciosamente.
     */
    @Test
    void validacionRechazaXmlDeUnTipoDeComprobanteValidadoContraElPerfilDeOtro() {
        XmlFacturaXsdValidator validator = new XmlFacturaXsdValidator();

        String xmlNotaCredito = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<NotaCreditoElectronica xmlns=\"" + TipoComprobantePerfil.NOTA_CREDITO.namespace() + "\">"
                + "</NotaCreditoElectronica>";

        assertThatThrownBy(() -> validator.validar(xmlNotaCredito, TipoComprobantePerfil.FACTURA_ELECTRONICA))
                .isInstanceOf(XmlFacturaInvalidoException.class);
    }

    private String leerClasspath(String classpath) throws IOException {
        byte[] bytes = new ClassPathResource(classpath).getInputStream().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
