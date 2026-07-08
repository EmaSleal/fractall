package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria pura de {@link ComprobanteXmlBlobFormat} -- sin Vault ni Postgres, verifica el
 * layout binario exacto documentado en su javadoc reimplementando aquí un pequeño deserializador
 * de prueba (la clase de producción solo expone {@code serializar}, ver su javadoc sobre por qué).
 */
class ComprobanteXmlBlobFormatTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void serializarProduceElPrefijoDeLongitudLaDekEnvueltaYElXmlCifradoEnEseOrden() {
        byte[] dekEnvuelta = "vault:v1:contenido-de-prueba-no-real".getBytes(StandardCharsets.UTF_8);
        byte[] xmlCifrado = bytesAleatorios(48);

        byte[] blob = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifrado);

        assertThat(blob).hasSize(4 + dekEnvuelta.length + xmlCifrado.length);

        ByteBuffer buffer = ByteBuffer.wrap(blob);
        int longitudLeida = buffer.getInt();
        assertThat(longitudLeida).isEqualTo(dekEnvuelta.length);

        byte[] dekLeida = new byte[longitudLeida];
        buffer.get(dekLeida);
        assertThat(dekLeida).isEqualTo(dekEnvuelta);

        byte[] xmlLeido = new byte[buffer.remaining()];
        buffer.get(xmlLeido);
        assertThat(xmlLeido).isEqualTo(xmlCifrado);
    }

    @Test
    void serializarConDekVaciaAunAsiPreservaElPrefijoDeLongitudCero() {
        byte[] dekEnvuelta = new byte[0];
        byte[] xmlCifrado = bytesAleatorios(16);

        byte[] blob = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifrado);

        assertThat(blob).hasSize(4 + xmlCifrado.length);
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        assertThat(buffer.getInt()).isZero();
        byte[] resto = new byte[buffer.remaining()];
        buffer.get(resto);
        assertThat(resto).isEqualTo(xmlCifrado);
    }

    @Test
    void serializarDosVecesConLosMismosBytesProduceElMismoBlob() {
        byte[] dekEnvuelta = "vault:v1:otra-dek".getBytes(StandardCharsets.UTF_8);
        byte[] xmlCifrado = bytesAleatorios(32);

        byte[] blob1 = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifrado);
        byte[] blob2 = ComprobanteXmlBlobFormat.serializar(Arrays.copyOf(dekEnvuelta, dekEnvuelta.length),
                Arrays.copyOf(xmlCifrado, xmlCifrado.length));

        assertThat(blob1).isEqualTo(blob2);
    }

    private static byte[] bytesAleatorios(int longitud) {
        byte[] bytes = new byte[longitud];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
