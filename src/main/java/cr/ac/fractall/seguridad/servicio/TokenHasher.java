package cr.ac.fractall.seguridad.servicio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash de tokens de un solo uso ({@code usuario_token.token_hash}, sección 3.1 del
 * documento de arquitectura). Solo el hash se persiste -- el token crudo únicamente existe
 * en memoria el tiempo suficiente para incluirlo en el correo de verificación.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String tokenCrudo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(tokenCrudo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException excepcion) {
            // SHA-256 es parte obligatoria de cualquier JDK conforme; esto no debería
            // ocurrir nunca en producción.
            throw new IllegalStateException("SHA-256 no disponible en este JDK", excepcion);
        }
    }
}
