package cr.ac.fractall.seguridad.servicio;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Codec Base32 (RFC 4648) mínimo, sin relleno ({@code =}) en la salida de
 * {@link #encode(byte[])} -- la convención que usan las apps autenticadoras (Google
 * Authenticator y equivalentes) al mostrar el secreto TOTP para entrada manual (sección 3.3).
 *
 * <p>Hand-rolled deliberadamente en vez de agregar {@code commons-codec} como dependencia
 * nueva: el algoritmo son ~30 líneas de desplazamiento de bits, ya se agregó
 * {@code javax.crypto.Mac} a mano para TOTP (ver {@code TotpService}), y {@code
 * java.util.Base64} del JDK NO sirve -- es un alfabeto y empaquetado de bits distintos, no
 * intercambiable con Base32.
 */
public final class Base32Codec {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32Codec() {
    }

    public static String encode(byte[] datos) {
        StringBuilder resultado = new StringBuilder();
        int buffer = 0;
        int bitsEnBuffer = 0;
        for (byte b : datos) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsEnBuffer += 8;
            while (bitsEnBuffer >= 5) {
                int indice = (buffer >> (bitsEnBuffer - 5)) & 0x1F;
                resultado.append(ALFABETO.charAt(indice));
                bitsEnBuffer -= 5;
            }
        }
        if (bitsEnBuffer > 0) {
            int indice = (buffer << (5 - bitsEnBuffer)) & 0x1F;
            resultado.append(ALFABETO.charAt(indice));
        }
        return resultado.toString();
    }

    /** Acepta entrada con o sin relleno {@code =}, y es insensible a mayúsculas/minúsculas. */
    public static byte[] decode(String base32) {
        String limpio = base32.trim().toUpperCase(Locale.ROOT).replace("=", "");
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsEnBuffer = 0;
        for (char c : limpio.toCharArray()) {
            int indice = ALFABETO.indexOf(c);
            if (indice < 0) {
                continue;
            }
            buffer = (buffer << 5) | indice;
            bitsEnBuffer += 5;
            if (bitsEnBuffer >= 8) {
                salida.write((buffer >> (bitsEnBuffer - 8)) & 0xFF);
                bitsEnBuffer -= 8;
            }
        }
        return salida.toByteArray();
    }
}
