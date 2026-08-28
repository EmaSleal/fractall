package cr.ac.fractall.seguridad.servicio;

/**
 * El token de invitación presentado a {@code POST /usuarios/invitacion/{token}/aceptar} (y,
 * en una rebanada futura, a {@code POST /auth/registro/invitacion}) no es utilizable: no existe
 * ninguna {@code invitacion_usuario} con ese hash, o la fila existe pero ya no está
 * {@code PENDIENTE} (venció, ya fue {@code ACEPTADA} o fue {@code REVOCADA}).
 *
 * <p>Un solo mensaje fijo para los 4 motivos (Fase B, ver design.md, sección
 * {@code InvitacionUsuarioService}: "un solo mensaje") -- distinguir el motivo exacto en la
 * respuesta filtraría si un token concreto existió alguna vez, el mismo criterio anti-
 * enumeración que ya aplica a {@code POST /usuarios/invitar}.
 */
public class InvitacionInvalidaException extends RuntimeException {

    public InvitacionInvalidaException() {
        super("La invitación no es válida, ya fue utilizada o expiró.");
    }
}
