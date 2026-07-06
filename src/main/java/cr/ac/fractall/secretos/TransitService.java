package cr.ac.fractall.secretos;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.VaultResponse;

/**
 * Envelope encryption vía el motor Transit de Vault (sección 6.1 de
 * {@code arquitectura-facturacion-electronica-cr.md}): una sola KEK maestra
 * ({@code transit/keys/empresa-datos-kek}), consistente con la decisión de que el costo
 * de cifrado no escala con el número de empresas -- se emite una DEK distinta por
 * fila/operación de cifrado en el momento de usarla.
 *
 * <p>El llamador debe usar la DEK en texto plano de {@link #generarDek()} de inmediato y
 * descartarla; solo su blob cifrado se persiste, y se recupera después vía
 * {@link #descifrarDek(byte[])}.
 *
 * <p>{@code transit/datakey/plaintext/...} no tiene una operación de alto nivel dedicada
 * en {@code spring-vault-core} 4.1.0 (a diferencia de cifrar/descifrar arbitrario, que sí
 * la tiene vía {@link org.springframework.vault.core.VaultTransitOperations}) -- se invoca
 * el endpoint crudo con {@link VaultOperations#write}.
 */
@Service
public class TransitService {

    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String DATAKEY_PATH = "transit/datakey/plaintext/" + TRANSIT_KEY;

    private final VaultOperations vaultOperations;

    public TransitService(VaultOperations vaultOperations) {
        this.vaultOperations = vaultOperations;
    }

    public Dek generarDek() {
        VaultResponse respuesta = vaultOperations.write(DATAKEY_PATH, Map.of());
        if (respuesta == null || !respuesta.hasData()) {
            throw new IllegalStateException("Vault no devolvió datos al generar la DEK en " + DATAKEY_PATH);
        }
        Map<String, Object> datos = respuesta.getRequiredData();
        String plaintextBase64 = (String) datos.get("plaintext");
        String ciphertext = (String) datos.get("ciphertext");

        byte[] plaintext = Base64.getDecoder().decode(plaintextBase64);
        byte[] cifrado = ciphertext.getBytes(StandardCharsets.UTF_8);
        return new Dek(plaintext, cifrado);
    }

    public byte[] descifrarDek(byte[] cifrado) {
        String ciphertext = new String(cifrado, StandardCharsets.UTF_8);
        Plaintext plaintext = vaultOperations.opsForTransit().decrypt(TRANSIT_KEY, Ciphertext.of(ciphertext));
        return plaintext.getPlaintext();
    }

    /**
     * Cifrado directo (no envelope) contra la misma KEK de Transit, para valores pequeños que
     * no justifican una DEK propia -- el secreto TOTP de MFA (~20 bytes, sección 3.3: "se
     * cifra a nivel de columna... vía la misma KEK de Transit... no consultado a Vault en
     * cada login"). A diferencia de {@link #generarDek()}, sí existe una operación de alto
     * nivel dedicada en {@code spring-vault-core} para esto ({@link
     * org.springframework.vault.core.VaultTransitOperations#encrypt(String, Plaintext)}), así
     * que no hace falta invocar el endpoint crudo.
     */
    public String cifrar(String texto) {
        Ciphertext ciphertext = vaultOperations.opsForTransit().encrypt(TRANSIT_KEY, Plaintext.of(texto));
        return ciphertext.getCiphertext();
    }

    /**
     * Contraparte de {@link #cifrar(String)}. Implica una llamada de red a Vault en cada
     * invocación -- aceptable aquí porque el secreto en claro es indispensable para correr el
     * algoritmo TOTP (ver {@code TotpService}), a diferencia de la DEK de
     * {@link #generarDek()}/{@link #descifrarDek(byte[])}, cuyo plaintext se usa una sola vez
     * y se descarta.
     */
    public String descifrar(String cifrado) {
        Plaintext plaintext = vaultOperations.opsForTransit().decrypt(TRANSIT_KEY, Ciphertext.of(cifrado));
        return plaintext.asString();
    }

    /**
     * DEK recién emitida por Transit: {@code plaintext} es de uso inmediato y debe
     * descartarse después de cifrar/descifrar los datos del tenant; {@code cifrado} es el
     * blob que se persiste (p. ej. en {@code empresa.certificado_referencia} o una columna
     * equivalente) y que luego se pasa de vuelta a {@link #descifrarDek(byte[])}.
     */
    public record Dek(byte[] plaintext, byte[] cifrado) {
    }
}
