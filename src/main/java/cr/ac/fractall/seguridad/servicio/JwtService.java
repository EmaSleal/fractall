package cr.ac.fractall.seguridad.servicio;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Firma y verificación de access tokens JWT.
 *
 * <p>Access token de vida corta (15 min, sección 2 de
 * {@code arquitectura-facturacion-electronica-cr.md}), firmado con HS256. También emite y
 * valida dos tokens de alcance mínimo, cada uno con su propio claim {@code proposito}
 * (ver {@link #CLAIM_PROPOSITO}):
 * <ul>
 *   <li>Selección de tenant (sección 3.2, punto 2): solo {@code usuarioId}, SIN
 *       {@code empresaId}, pensado para ser intercambiado de inmediato contra
 *       {@code POST /auth/seleccionar-tenant}.</li>
 *   <li>MFA pendiente (sección 3.3): {@code usuarioId} + {@code empresaId} ya resueltos,
 *       pensado para ser intercambiado contra {@code POST /auth/mfa/enrolar},
 *       {@code /auth/mfa/confirmar} o {@code /auth/mfa/verificar}.</li>
 * </ul>
 * Ninguno de los dos debe usarse jamás como bearer token general (ver
 * {@code JwtAuthenticationFilter} y {@code JwtTenantFilter}, que rechazan cualquier token con
 * un {@code proposito} no nulo vía {@link #esTokenDePropositoEspecial(String)}) -- ese chequeo
 * es deliberadamente genérico, no un chequeo por tipo, para que un tercer token de alcance
 * mínimo futuro quede cubierto automáticamente sin tocar los filtros de nuevo.
 * No maneja refresh tokens revocables ({@code sesion_refresh_token}) -- esos son tokens
 * opacos de base de datos, no JWT (ver {@code RefreshTokenService}).
 */
@Service
public class JwtService {

    private static final long EXPIRACION_MINUTOS = 15;

    /**
     * 5 minutos: el único trabajo de este token es viajar del login a
     * {@code POST /auth/seleccionar-tenant} en el mismo flujo de UI, de forma prácticamente
     * inmediata -- una ventana corta reduce el impacto de una posible fuga sin afectar UX.
     */
    private static final long EXPIRACION_SELECCION_TENANT_MINUTOS = 5;

    /**
     * 10 minutos: a diferencia del token de selección de tenant (un intercambio máquina-a-
     * máquina prácticamente instantáneo), este token debe sobrevivir a que una persona lea un
     * QR con su teléfono, abra su app autenticadora y escriba un código de 6 dígitos -- una
     * ventana más generosa que los 5 minutos de selección de tenant, pero igual de corta
     * (sección 3.3), ya que su único destino son los 3 endpoints {@code /auth/mfa/*}.
     */
    private static final long EXPIRACION_MFA_PENDIENTE_MINUTOS = 10;

    private static final String CLAIM_EMPRESA_ID = "empresaId";

    /** Marca que distingue un token de alcance mínimo (cualquiera) de un access token normal. */
    private static final String CLAIM_PROPOSITO = "proposito";
    private static final String PROPOSITO_SELECCION_TENANT = "SELECCION_TENANT";
    private static final String PROPOSITO_MFA_PENDIENTE = "MFA_PENDIENTE";

    private final SecretKey clavefirma;

    public JwtService(@Value("${application.security.jwt.secret}") String secreto) {
        this.clavefirma = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
    }

    public String generarToken(UUID usuarioId, UUID empresaId) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_EMPRESA_ID, empresaId.toString())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(EXPIRACION_MINUTOS, ChronoUnit.MINUTES)))
                .signWith(clavefirma, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Valida firma y expiración sin lanzar excepción — pensado para usarse en un filtro
     * donde un token ausente o inválido simplemente deja la solicitud sin autenticar, en
     * lugar de interrumpir la cadena con una excepción no controlada.
     */
    public boolean esValido(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException excepcion) {
            return false;
        }
    }

    public UUID extraerUsuarioId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UUID extraerEmpresaId(String token) {
        return UUID.fromString(parseClaims(token).get(CLAIM_EMPRESA_ID, String.class));
    }

    /**
     * Token de alcance mínimo (sección 3.2, punto 2): solo {@code usuarioId} en el
     * {@code subject}, sin {@code empresaId} -- llamar {@link #extraerEmpresaId(String)}
     * sobre un token de este tipo falla, porque el claim no existe. {@link #extraerUsuarioId(String)}
     * sí funciona sobre ambos tipos de token, ya que el {@code subject} siempre es el
     * {@code usuarioId}.
     */
    public String generarTokenSeleccionTenant(UUID usuarioId) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_PROPOSITO, PROPOSITO_SELECCION_TENANT)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(EXPIRACION_SELECCION_TENANT_MINUTOS, ChronoUnit.MINUTES)))
                .signWith(clavefirma, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * {@code true} si el token trae la marca {@code proposito = SELECCION_TENANT}. Debe
     * llamarse SIEMPRE después de confirmar {@link #esValido(String)} -- a diferencia de ese
     * método, este no atrapa {@link JwtException}, porque {@code AuthController} (el único
     * llamador) ya garantiza esa validación previa en el mismo orden de evaluación de la
     * condición.
     */
    public boolean esTokenSeleccionTenant(String token) {
        return PROPOSITO_SELECCION_TENANT.equals(parseClaims(token).get(CLAIM_PROPOSITO, String.class));
    }

    /**
     * Token de alcance mínimo (sección 3.3): {@code usuarioId} en el {@code subject} +
     * {@code empresaId} (a diferencia del token de selección de tenant, aquí la empresa YA
     * está resuelta -- lo único pendiente es completar o confirmar el enrolamiento MFA). Su
     * único destino son {@code POST /auth/mfa/enrolar}, {@code /auth/mfa/confirmar} y
     * {@code /auth/mfa/verificar}.
     */
    public String generarTokenMfaPendiente(UUID usuarioId, UUID empresaId) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_EMPRESA_ID, empresaId.toString())
                .claim(CLAIM_PROPOSITO, PROPOSITO_MFA_PENDIENTE)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(EXPIRACION_MFA_PENDIENTE_MINUTOS, ChronoUnit.MINUTES)))
                .signWith(clavefirma, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * {@code true} si el token trae la marca {@code proposito = MFA_PENDIENTE}. Mismo
     * contrato que {@link #esTokenSeleccionTenant(String)}: llamar SIEMPRE después de
     * confirmar {@link #esValido(String)}.
     */
    public boolean esTokenMfaPendiente(String token) {
        return PROPOSITO_MFA_PENDIENTE.equals(parseClaims(token).get(CLAIM_PROPOSITO, String.class));
    }

    /**
     * {@code true} si el token trae CUALQUIER {@code proposito} no nulo -- es decir, si es
     * alguno de los tokens de alcance mínimo (selección de tenant o MFA pendiente) y no un
     * access token normal. Usado por {@code JwtAuthenticationFilter} y {@code JwtTenantFilter}
     * para rechazar de forma genérica cualquier token especial como bearer token de propósito
     * general, sin necesidad de listar cada tipo por separado (ver el javadoc de la clase).
     * Mismo contrato que {@link #esTokenSeleccionTenant(String)}: llamar SIEMPRE después de
     * confirmar {@link #esValido(String)}.
     */
    public boolean esTokenDePropositoEspecial(String token) {
        return parseClaims(token).get(CLAIM_PROPOSITO, String.class) != null;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(clavefirma)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
