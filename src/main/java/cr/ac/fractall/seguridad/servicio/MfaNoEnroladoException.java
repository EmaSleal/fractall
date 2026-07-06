package cr.ac.fractall.seguridad.servicio;

/**
 * {@code usuario.mfa_secret_cifrado} todavía es {@code null} -- usada por
 * {@code MfaService#confirmar} cuando se llama sin haber invocado antes
 * {@code POST /auth/mfa/enrolar} para ese usuario. No debería ser alcanzable en el flujo
 * normal de UI (el cliente siempre enrola antes de confirmar), pero se rechaza de forma
 * explícita en vez de fallar de forma críptica más adelante -- mismo criterio que
 * {@code LoginService#SinEmpresaActivaException}.
 */
public class MfaNoEnroladoException extends RuntimeException {
}
