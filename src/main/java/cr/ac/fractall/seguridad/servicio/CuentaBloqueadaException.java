package cr.ac.fractall.seguridad.servicio;

/**
 * {@code usuario.bloqueada_hasta} todavía en el futuro -- rechazo con mensaje explícitamente
 * DISTINTO al de credenciales inválidas (sección 3.4: bloqueo por fuerza bruta).
 */
public class CuentaBloqueadaException extends RuntimeException {
}
