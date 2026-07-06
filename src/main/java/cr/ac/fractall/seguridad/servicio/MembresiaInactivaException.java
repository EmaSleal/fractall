package cr.ac.fractall.seguridad.servicio;

/**
 * El usuario no tiene una membresía {@code ACTIVO} en {@code usuario_empresa} para la
 * empresa solicitada -- usada por {@code SesionService} en {@code seleccionar-tenant},
 * {@code cambiar-tenant} y {@code refrescar}.
 */
public class MembresiaInactivaException extends RuntimeException {
}
