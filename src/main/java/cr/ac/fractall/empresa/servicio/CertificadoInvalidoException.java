package cr.ac.fractall.empresa.servicio;

/**
 * El archivo {@code .p12} y/o el PIN proporcionados no abren un keystore PKCS12 válido (ver
 * {@code EmpresaService#cargarCertificado}). Se lanza ANTES de escribir nada en Vault o en la
 * base de datos -- la validación del PIN es siempre el primer paso.
 */
public class CertificadoInvalidoException extends RuntimeException {

    public CertificadoInvalidoException(String message, Throwable cause) {
        super(message, cause);
    }
}
