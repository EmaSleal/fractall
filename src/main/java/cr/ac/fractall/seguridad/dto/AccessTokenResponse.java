package cr.ac.fractall.seguridad.dto;

import java.util.UUID;

/**
 * Respuesta de todo endpoint que emite un access token completo ya resuelto contra una
 * empresa: login directo (1 empresa), {@code seleccionar-tenant}, {@code cambiar-tenant} y
 * {@code refrescar}. {@code refreshToken} es {@code null} cuando la operación no emite uno
 * nuevo ({@code cambiar-tenant}, {@code refrescar}).
 */
public record AccessTokenResponse(String accessToken, String refreshToken, UUID empresaId) {
}
