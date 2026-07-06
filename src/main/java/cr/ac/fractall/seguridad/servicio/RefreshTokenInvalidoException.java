package cr.ac.fractall.seguridad.servicio;

/**
 * El refresh token presentado en {@code POST /auth/refrescar} no existe, ya fue revocado, o
 * ya expiró -- las tres causas se tratan como una sola, sin distinguir el motivo en la
 * respuesta HTTP.
 */
public class RefreshTokenInvalidoException extends RuntimeException {
}
