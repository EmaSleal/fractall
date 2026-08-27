package cr.ac.fractall.seguridad.servicio;

/**
 * {@code rolCodigo} recibido en {@code POST /usuarios/invitar} no corresponde a ningún
 * {@code Rol} existente (Fase B, ver design.md, sección "InvitacionUsuarioService": "un código
 * inexistente ... se rechaza con 400"). Antes de esta excepción, {@link
 * InvitacionUsuarioService#emitir} fallaba con un {@code IllegalStateException} sin manejar, que
 * escalaba como un 500 crudo -- ver {@code GlobalExceptionHandler#manejarReglaDeNegocioInvalida}.
 */
public class RolInvitacionInvalidoException extends RuntimeException {

    public RolInvitacionInvalidoException(String rolCodigo) {
        super("Rol " + rolCodigo + " no encontrado");
    }
}
