package cr.ac.fractall.seguridad.servicio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import cr.ac.fractall.secretos.TransitService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Enrolamiento y verificación MFA/TOTP (sección 3.3): los 3 endpoints {@code /auth/mfa/*}
 * comparten este servicio, que a su vez es el único punto que toca
 * {@code usuario.mfa_secret_cifrado} -- ningún otro servicio del paquete lee o escribe esa
 * columna directamente.
 *
 * <p>El secreto TOTP vive cifrado en la propia fila de {@code usuario} (columna BYTEA), NO en
 * el almacén KV de Vault -- se cifra/descifra directo contra la KEK de Transit vía
 * {@link TransitService#cifrar(String)}/{@link TransitService#descifrar(String)} (envelope
 * encryption vía {@code generarDek()}/{@code descifrarDek(byte[])} sería complejidad
 * innecesaria para un valor de ~20 bytes, ver el javadoc de {@code TransitService}).
 *
 * <p>Emite el access token + refresh token completos en {@link #confirmar} y
 * {@link #verificar} (mismo par {@code JwtService}/{@code RefreshTokenService} que
 * {@code LoginService}/{@code SesionService}) porque, igual que esos dos servicios, es quien
 * resuelve el último paso pendiente antes de una sesión completa -- así {@code AuthController}
 * se mantiene igual de delgado en los 3 endpoints nuevos que en los ya existentes.
 */
@Service
public class MfaService {

    /** 160 bits: tamaño convencional de secreto para TOTP con HmacSHA1 (ver {@code TotpService}). */
    private static final int SECRETO_BYTES = 20;

    private static final String EMISOR = "Fractall";
    private static final int QR_TAMANO_PX = 300;

    private final UsuarioRepository usuarioRepository;
    private final TransitService transitService;
    private final TotpService totpService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaService(
            UsuarioRepository usuarioRepository,
            TransitService transitService,
            TotpService totpService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.usuarioRepository = usuarioRepository;
        this.transitService = transitService;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Genera un secreto TOTP nuevo, lo cifra y lo persiste -- NO marca
     * {@code mfaHabilitado = true} todavía, eso ocurre recién en {@link #confirmar} tras
     * validar el primer código (sección 3.3: el enrolamiento no se da por completo hasta
     * confirmarse).
     */
    @Transactional
    public EnrolamientoResultado enrolar(UUID usuarioId) {
        Usuario usuario = obtenerUsuario(usuarioId);
        if (usuario.isMfaHabilitado()) {
            throw new MfaYaHabilitadoException();
        }

        byte[] secreto = new byte[SECRETO_BYTES];
        secureRandom.nextBytes(secreto);
        String secretoBase32 = Base32Codec.encode(secreto);

        String secretoCifrado = transitService.cifrar(secretoBase32);
        usuario.setMfaSecretCifrado(secretoCifrado.getBytes(StandardCharsets.UTF_8));
        usuario.setUpdateDate(LocalDateTime.now());
        usuarioRepository.save(usuario);

        String qrCodeBase64 = generarQrBase64(usuario.getEmail(), secretoBase32);
        return new EnrolamientoResultado(qrCodeBase64, secretoBase32);
    }

    /**
     * Primer código TOTP tras {@link #enrolar}: si es válido, activa {@code mfaHabilitado} y
     * completa el login emitiendo un access token + refresh token completos (criterio de
     * salida de la Fase 4: registro -> verificación -> login -> enrolamiento MFA de punta a
     * punta). Si es inválido, NO activa MFA.
     */
    @Transactional
    public TokensAcceso confirmar(UUID usuarioId, UUID empresaId, String codigo) {
        Usuario usuario = obtenerUsuario(usuarioId);
        byte[] secreto = descifrarSecreto(usuario);

        if (!totpService.verificar(secreto, codigo)) {
            throw new CodigoMfaInvalidoException();
        }

        usuario.setMfaHabilitado(true);
        usuario.setUpdateDate(LocalDateTime.now());
        usuarioRepository.save(usuario);

        return emitirTokens(usuarioId, empresaId);
    }

    /** Login posterior de un usuario ya enrolado: mismo chequeo, sin tocar {@code mfaHabilitado}. */
    public TokensAcceso verificar(UUID usuarioId, UUID empresaId, String codigo) {
        Usuario usuario = obtenerUsuario(usuarioId);
        byte[] secreto = descifrarSecreto(usuario);

        if (!totpService.verificar(secreto, codigo)) {
            throw new CodigoMfaInvalidoException();
        }

        return emitirTokens(usuarioId, empresaId);
    }

    private TokensAcceso emitirTokens(UUID usuarioId, UUID empresaId) {
        String accessToken = jwtService.generarToken(usuarioId, empresaId);
        String refreshToken = refreshTokenService.emitir(usuarioId);
        return new TokensAcceso(accessToken, refreshToken, empresaId);
    }

    private Usuario obtenerUsuario(UUID usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Token MFA pendiente referencia un usuario_id inexistente: " + usuarioId));
    }

    private byte[] descifrarSecreto(Usuario usuario) {
        if (usuario.getMfaSecretCifrado() == null) {
            throw new MfaNoEnroladoException();
        }
        String cifrado = new String(usuario.getMfaSecretCifrado(), StandardCharsets.UTF_8);
        String secretoBase32 = transitService.descifrar(cifrado);
        return Base32Codec.decode(secretoBase32);
    }

    /** {@code otpauth://totp/Fractall:{email}?secret={base32}&issuer=Fractall}, como PNG en base64. */
    private String generarQrBase64(String email, String secretoBase32) {
        String etiqueta = urlEncode(EMISOR + ":" + email);
        String uri = "otpauth://totp/" + etiqueta + "?secret=" + secretoBase32 + "&issuer=" + urlEncode(EMISOR);

        try {
            BitMatrix matriz = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, QR_TAMANO_PX, QR_TAMANO_PX);
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matriz, "PNG", salida);
            return Base64.getEncoder().encodeToString(salida.toByteArray());
        } catch (WriterException | IOException excepcion) {
            throw new IllegalStateException("No se pudo generar el QR de enrolamiento MFA", excepcion);
        }
    }

    private String urlEncode(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** {@code secretoBase32} se devuelve como respaldo de entrada manual (estándar en apps autenticadoras). */
    public record EnrolamientoResultado(String qrCodeBase64Png, String secretoBase32) {
    }
}
