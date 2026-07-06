package cr.ac.fractall.seguridad.servicio;

/**
 * Correo inexistente o contraseña incorrecta -- deliberadamente el MISMO mensaje para ambos
 * casos (anti-enumeración de cuentas), y distinto del mensaje de cuenta no verificada o
 * cuenta bloqueada (sección 3.2 y 3.4 del documento de arquitectura).
 */
public class CredencialesInvalidasException extends RuntimeException {
}
