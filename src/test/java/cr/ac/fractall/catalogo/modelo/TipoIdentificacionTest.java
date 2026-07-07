package cr.ac.fractall.catalogo.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria pura (sin contexto de Spring) del enum portado {@link TipoIdentificacion} --
 * ver sección 4.11 de {@code arquitectura-facturacion-electronica-cr.md}: valida los 4 tipos
 * (física, jurídica, DIMEX, NITE) consumidos por {@code ClienteService}.
 */
class TipoIdentificacionTest {

    @Test
    void cedulaFisicaValidaNueveDigitos() {
        assertThat(TipoIdentificacion.CEDULA_FISICA.validarNumero("107890123")).isTrue();
    }

    @Test
    void cedulaFisicaRechazaLongitudIncorrecta() {
        assertThat(TipoIdentificacion.CEDULA_FISICA.validarNumero("1078901")).isFalse();
    }

    @Test
    void cedulaJuridicaValidaDiezDigitos() {
        assertThat(TipoIdentificacion.CEDULA_JURIDICA.validarNumero("3101123456")).isTrue();
    }

    @Test
    void diMexValidaOnceODoceDigitos() {
        assertThat(TipoIdentificacion.DIMEX.validarNumero("12345678901")).isTrue();
        assertThat(TipoIdentificacion.DIMEX.validarNumero("123456789012")).isTrue();
        assertThat(TipoIdentificacion.DIMEX.validarNumero("1234567890")).isFalse();
    }

    @Test
    void niteValidaDiezDigitos() {
        assertThat(TipoIdentificacion.NITE.validarNumero("1234567890")).isTrue();
        assertThat(TipoIdentificacion.NITE.validarNumero("123456789")).isFalse();
    }

    @Test
    void rechazaNumeroConLetras() {
        assertThat(TipoIdentificacion.CEDULA_FISICA.validarNumero("10789012A")).isFalse();
    }

    @Test
    void fromCodigoResuelveLosCuatroTipos() {
        assertThat(TipoIdentificacion.fromCodigo("01")).isEqualTo(TipoIdentificacion.CEDULA_FISICA);
        assertThat(TipoIdentificacion.fromCodigo("02")).isEqualTo(TipoIdentificacion.CEDULA_JURIDICA);
        assertThat(TipoIdentificacion.fromCodigo("03")).isEqualTo(TipoIdentificacion.DIMEX);
        assertThat(TipoIdentificacion.fromCodigo("04")).isEqualTo(TipoIdentificacion.NITE);
    }

    @Test
    void fromCodigoRechazaCodigoDesconocido() {
        assertThatThrownBy(() -> TipoIdentificacion.fromCodigo("99"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
