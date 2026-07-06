package cr.ac.fractall.seguridad.servicio;

/**
 * El usuario autenticó correctamente pero no tiene ninguna membresía {@code ACTIVO} en
 * {@code usuario_empresa}. No debería ser alcanzable en la práctica -- el registro siempre
 * crea exactamente una membresía (ver {@code RegistroService}) -- pero se maneja de forma
 * explícita en vez de dejar que una lista vacía provoque una excepción no controlada más
 * adelante (ej. al intentar leer el primer elemento).
 */
public class SinEmpresaActivaException extends RuntimeException {
}
