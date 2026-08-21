package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;

/**
 * Fase B, tarea 1.3: confirma que {@link XmlFacturaXsdValidator} compila los 4 esquemas (uno por
 * cada {@link TipoComprobantePerfil}) en el constructor -- el {@code EnumMap} debe quedar
 * totalmente poblado, sin claves faltantes que produzcan un {@code NullPointerException} recién
 * en tiempo de validación.
 *
 * <p>No usa contexto de Spring -- {@link XmlFacturaXsdValidator} no depende de ningún bean.
 */
class XmlFacturaXsdValidatorTest {

    @Test
    void construyeYCargaLosCuatroEsquemasSinLanzar() {
        assertThatCode(XmlFacturaXsdValidator::new).doesNotThrowAnyException();
    }

    @Test
    void validarResuelveUnEsquemaParaCadaPerfilSinNullPointerException() {
        XmlFacturaXsdValidator validator = new XmlFacturaXsdValidator();
        String xmlVacio = "<?xml version=\"1.0\"?><root/>";

        for (TipoComprobantePerfil perfil : TipoComprobantePerfil.values()) {
            // Un XML deliberadamente inválido contra CUALQUIER esquema real -- el punto no es que
            // pase, sino que falle con XmlFacturaInvalidoException (esquema resuelto y aplicado)
            // en vez de con NullPointerException (clave ausente del EnumMap para ese perfil).
            assertThatThrownBy(() -> validator.validar(xmlVacio, perfil))
                    .as("perfil %s debe tener un Schema cargado en el EnumMap", perfil)
                    .isInstanceOf(XmlFacturaInvalidoException.class);
        }
    }
}
