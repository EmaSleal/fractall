package cr.ac.fractall.seguridad.servicio;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * TOTP (RFC 6238, sobre HOTP de RFC 4226) hand-rolled con {@code javax.crypto.Mac} -- no hay
 * ninguna librería TOTP en el classpath (sección 3.3) y el algoritmo del núcleo es compacto y
 * bien conocido, así que no se agrega una dependencia nueva solo para esto.
 *
 * <p>Convención de Google Authenticator (y prácticamente cualquier app autenticadora real):
 * {@code HmacSHA1}, 6 dígitos, paso de 30 segundos -- DELIBERADAMENTE distinta del ejemplo de
 * 8 dígitos del Apéndice B de la propia RFC 6238 (ese apéndice ilustra el algoritmo con más
 * dígitos para reducir colisiones en su tabla de prueba, no es la convención que las apps
 * cliente esperan). {@link #generarCodigo(byte[], long, int, String)} es paquete-visible y
 * agnóstico de dígitos/algoritmo precisamente para poder validarse contra los vectores de
 * prueba de esa RFC (8 dígitos) en un test dedicado, sin que la producción tenga que usar esa
 * misma convención.
 */
@Service
public class TotpService {

    private static final int PASO_SEGUNDOS = 30;
    private static final int DIGITOS = 6;
    private static final String ALGORITMO_HMAC_SHA1 = "HmacSHA1";

    /**
     * ±1 paso (± 30 segundos): tolerancia estándar de clock drift entre el reloj del servidor
     * y el del teléfono -- sin esto, cualquier desfase de reloj mínimo (o el tiempo que tarda
     * la persona en escribir el código) rechazaría códigos válidos de forma intermitente.
     */
    private static final int TOLERANCIA_PASOS = 1;

    /** Código TOTP vigente ahora mismo, con la convención de producción (6 dígitos, SHA1, 30s). */
    public String generarCodigoActual(byte[] secreto) {
        return generarCodigo(secreto, contadorActual(), DIGITOS, ALGORITMO_HMAC_SHA1);
    }

    /**
     * {@code true} si {@code codigo} coincide con el TOTP del paso actual o de alguno de los
     * {@value #TOLERANCIA_PASOS} pasos inmediatamente antes/después (tolerancia de clock drift).
     */
    public boolean verificar(byte[] secreto, String codigo) {
        long contadorActual = contadorActual();
        for (int desplazamiento = -TOLERANCIA_PASOS; desplazamiento <= TOLERANCIA_PASOS; desplazamiento++) {
            String candidato = generarCodigo(secreto, contadorActual + desplazamiento, DIGITOS, ALGORITMO_HMAC_SHA1);
            if (candidato.equals(codigo)) {
                return true;
            }
        }
        return false;
    }

    private long contadorActual() {
        return Instant.now().getEpochSecond() / PASO_SEGUNDOS;
    }

    /**
     * Núcleo HOTP (RFC 4226) + ventana de tiempo TOTP (RFC 6238): HMAC del contador de pasos
     * de 8 bytes big-endian, seguido de truncamiento dinámico. Paquete-visible para que el
     * test de vectores de la RFC 6238 (que usa 8 dígitos, no la convención de 6 de
     * producción) pueda invocarlo directamente.
     */
    static String generarCodigo(byte[] secreto, long contador, int digitos, String algoritmoHmac) {
        byte[] datosContador = ByteBuffer.allocate(8).putLong(contador).array();
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(algoritmoHmac);
            mac.init(new SecretKeySpec(secreto, algoritmoHmac));
            hash = mac.doFinal(datosContador);
        } catch (GeneralSecurityException excepcion) {
            // HmacSHA1/HmacSHA256 son parte obligatoria de cualquier JDK conforme; esto no
            // debería ocurrir nunca en producción (mismo criterio que TokenHasher).
            throw new IllegalStateException("Algoritmo HMAC no disponible en este JDK: " + algoritmoHmac, excepcion);
        }

        int offset = hash[hash.length - 1] & 0x0F;
        int binario = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        int modulo = (int) Math.pow(10, digitos);
        int codigo = binario % modulo;
        return String.format("%0" + digitos + "d", codigo);
    }
}
