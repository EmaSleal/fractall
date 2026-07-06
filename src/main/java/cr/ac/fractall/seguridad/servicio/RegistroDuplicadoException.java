package cr.ac.fractall.seguridad.servicio;

/** Ya existe una cuenta {@code usuario} con el correo solicitado en el registro. */
public class RegistroDuplicadoException extends RuntimeException {

    public RegistroDuplicadoException(String message) {
        super(message);
    }
}
