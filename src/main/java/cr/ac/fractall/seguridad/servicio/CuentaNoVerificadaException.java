package cr.ac.fractall.seguridad.servicio;

/**
 * {@code usuario.estado = 'PENDIENTE_VERIFICACION'} -- rechazo con mensaje explícitamente
 * DISTINTO al de credenciales inválidas (sección 3.1: "Bloqueo de acceso hasta verificación").
 */
public class CuentaNoVerificadaException extends RuntimeException {
}
