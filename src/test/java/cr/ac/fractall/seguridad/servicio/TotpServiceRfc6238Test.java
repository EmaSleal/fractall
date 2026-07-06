package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Valida el núcleo HOTP/TOTP hand-rolled de {@link TotpService} contra los vectores de
 * prueba PUBLICADOS por la propia RFC 6238 (Apéndice B, tabla con algoritmo {@code HMAC-SHA1}):
 * la única forma de probar que una implementación hand-rolled es realmente correcta, no solo
 * internamente auto-consistente (ver el javadoc de {@link TotpService}).
 *
 * <p>Esos vectores usan la convención de la propia RFC (8 dígitos, secreto ASCII de 20 bytes
 * {@code "12345678901234567890"}, paso de 30s, T0 = 0) -- DISTINTA de la convención de
 * producción (6 dígitos, ver {@link TotpService#generarCodigoActual}). Por eso este test
 * invoca directamente el método paquete-visible {@link TotpService#generarCodigo(byte[], long,
 * int, String)}, agnóstico de dígitos/algoritmo, en vez de la API pública.
 */
class TotpServiceRfc6238Test {

    private static final byte[] SECRETO_SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final int PASO_SEGUNDOS = 30;
    private static final int DIGITOS_RFC = 8;
    private static final String ALGORITMO_SHA1 = "HmacSHA1";

    @ParameterizedTest(name = "T={0}s -> {1}")
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037"
    })
    void generaElCodigoEsperadoParaCadaVectorDeLaRfc(long tiempoEnSegundos, String codigoEsperado) {
        long contador = tiempoEnSegundos / PASO_SEGUNDOS;
        String codigo = TotpService.generarCodigo(SECRETO_SHA1, contador, DIGITOS_RFC, ALGORITMO_SHA1);
        assertThat(codigo).isEqualTo(codigoEsperado);
    }

    @org.junit.jupiter.api.Test
    void generaCodigoDeSeisDigitosConLaConvencionDeProduccion() {
        // No es un vector de la RFC (que solo publica ejemplos de 8 dígitos): confirma que la
        // convención de producción (6 dígitos, ver TotpService#generarCodigoActual) igual
        // produce un código bien formado -- longitud fija, incluyendo ceros a la izquierda.
        String codigo = TotpService.generarCodigo(SECRETO_SHA1, 1L, 6, ALGORITMO_SHA1);
        assertThat(codigo).hasSize(6);
        assertThat(codigo).containsOnlyDigits();
    }
}
