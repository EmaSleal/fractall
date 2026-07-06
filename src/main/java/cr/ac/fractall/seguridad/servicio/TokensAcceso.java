package cr.ac.fractall.seguridad.servicio;

import java.util.UUID;

/**
 * Resultado compartido por {@code LoginService} (login directo de una sola empresa) y
 * {@code SesionService} ({@code seleccionar-tenant}, {@code cambiar-tenant}, {@code refrescar}):
 * un access token JWT completo, ya resuelto contra una empresa concreta.
 *
 * <p>{@code refreshToken} es {@code null} cuando la operación NO emite uno nuevo --
 * {@code cambiar-tenant} y {@code refrescar} deliberadamente no rotan el refresh token
 * existente (ver {@code AuthController}, sección 3.2 punto 3 y sección 2 del documento de
 * arquitectura).
 */
public record TokensAcceso(String accessToken, String refreshToken, UUID empresaId) {
}
