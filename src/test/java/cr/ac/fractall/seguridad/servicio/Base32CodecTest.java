package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Valida {@link Base32Codec} contra los vectores de prueba publicados en RFC 4648 (sección
 * 10) -- sin el relleno {@code =} final, ya que {@link Base32Codec#encode(byte[])} lo omite
 * deliberadamente (convención de apps autenticadoras, ver su javadoc).
 */
class Base32CodecTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "'', ''",
            "f, MY",
            "fo, MZXQ",
            "foo, MZXW6",
            "foob, MZXW6YQ",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI"
    })
    void codificaYDecodificaLosVectoresDeLaRfc4648(String textoPlano, String base32Esperado) {
        byte[] datos = textoPlano.getBytes(StandardCharsets.US_ASCII);

        assertThat(Base32Codec.encode(datos)).isEqualTo(base32Esperado);
        assertThat(Base32Codec.decode(base32Esperado)).isEqualTo(datos);
    }

    @org.junit.jupiter.api.Test
    void decodificaIgnorandoRellenoYMayusculasMinusculas() {
        assertThat(Base32Codec.decode("mzxw6===")).isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
    }
}
