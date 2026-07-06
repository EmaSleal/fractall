package cr.ac.fractall.secretos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria pura de {@link EnvelopeCipher} -- sin Vault ni Postgres, la DEK en claro se
 * simula con bytes aleatorios propios, ya que {@link EnvelopeCipher} solo conoce AES-GCM, no
 * cómo se obtuvo la clave (esa responsabilidad es de {@code TransitService#generarDek()}).
 */
class EnvelopeCipherTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] claveAes256() {
        byte[] clave = new byte[32];
        RANDOM.nextBytes(clave);
        return clave;
    }

    @Test
    void cifrarYLuegoDescifrarConLaMismaClaveDevuelveLosMismosBytesOriginales() {
        byte[] clave = claveAes256();
        byte[] original = "contenido-binario-de-un-.p12-de-prueba".getBytes(StandardCharsets.UTF_8);

        byte[] cifrado = EnvelopeCipher.cifrar(clave, original);
        assertThat(cifrado).isNotEqualTo(original);

        byte[] recuperado = EnvelopeCipher.descifrar(clave, cifrado);
        assertThat(recuperado).isEqualTo(original);
    }

    @Test
    void cifrarDosVecesElMismoContenidoProduceSalidasDistintasPorElIvAleatorio() {
        byte[] clave = claveAes256();
        byte[] original = "mismo-contenido".getBytes(StandardCharsets.UTF_8);

        byte[] cifrado1 = EnvelopeCipher.cifrar(clave, original);
        byte[] cifrado2 = EnvelopeCipher.cifrar(clave, original);

        assertThat(cifrado1).isNotEqualTo(cifrado2);
        assertThat(EnvelopeCipher.descifrar(clave, cifrado1)).isEqualTo(original);
        assertThat(EnvelopeCipher.descifrar(clave, cifrado2)).isEqualTo(original);
    }

    @Test
    void descifrarConUnaClaveDistintaFalla() {
        byte[] claveCorrecta = claveAes256();
        byte[] claveIncorrecta = claveAes256();
        byte[] cifrado = EnvelopeCipher.cifrar(claveCorrecta, "dato-sensible".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> EnvelopeCipher.descifrar(claveIncorrecta, cifrado))
                .isInstanceOf(IllegalStateException.class);
    }
}
