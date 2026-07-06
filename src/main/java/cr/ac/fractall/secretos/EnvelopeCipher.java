package cr.ac.fractall.secretos;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado/descifrado AES-GCM de blobs arbitrarios (ej. el {@code .p12} de la Fase 5) usando el
 * texto plano de una DEK ya emitida por {@link TransitService#generarDek()} -- envelope
 * encryption (sección 6.1 y 6.4 de {@code arquitectura-facturacion-electronica-cr.md}): esta
 * clase solo conoce AES-GCM, nunca cómo se obtuvo ni cómo se persiste la DEK, esa
 * responsabilidad es exclusiva del llamador ({@code TransitService}/{@code EmpresaService}).
 *
 * <p>Convención de formato: los primeros {@value #IV_BYTES} bytes de la salida de
 * {@link #cifrar(byte[], byte[])} son el IV de GCM (generado aleatoriamente en cada llamada,
 * nunca reutilizado con la misma clave), seguidos del ciphertext+tag. {@link
 * #descifrar(byte[], byte[])} asume exactamente esa misma convención.
 *
 * <p>El llamador es responsable de descartar la DEK en texto plano inmediatamente después de
 * usarla (ver el javadoc de {@link TransitService.Dek}) -- esta clase no retiene ninguna copia
 * de la clave más allá de la propia llamada.
 */
public final class EnvelopeCipher {

    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final String ALGORITMO_CLAVE = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EnvelopeCipher() {
    }

    public static byte[] cifrar(byte[] claveDek, byte[] datosClaros) {
        byte[] iv = new byte[IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(claveDek, ALGORITMO_CLAVE),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(datosClaros);

            byte[] resultado = new byte[IV_BYTES + cifrado.length];
            System.arraycopy(iv, 0, resultado, 0, IV_BYTES);
            System.arraycopy(cifrado, 0, resultado, IV_BYTES, cifrado.length);
            return resultado;
        } catch (GeneralSecurityException excepcion) {
            throw new IllegalStateException("No se pudo cifrar el blob con AES-GCM", excepcion);
        }
    }

    public static byte[] descifrar(byte[] claveDek, byte[] datosCifrados) {
        try {
            byte[] iv = Arrays.copyOfRange(datosCifrados, 0, IV_BYTES);
            byte[] cifrado = Arrays.copyOfRange(datosCifrados, IV_BYTES, datosCifrados.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(claveDek, ALGORITMO_CLAVE),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(cifrado);
        } catch (GeneralSecurityException | IllegalArgumentException excepcion) {
            throw new IllegalStateException("No se pudo descifrar el blob con AES-GCM -- clave incorrecta "
                    + "o datos corruptos", excepcion);
        }
    }
}
