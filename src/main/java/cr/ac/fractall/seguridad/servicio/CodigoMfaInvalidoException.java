package cr.ac.fractall.seguridad.servicio;

/**
 * El código TOTP de 6 dígitos no coincide con el secreto MFA del usuario (dentro de la
 * tolerancia de ±1 paso, ver {@code TotpService#verificar}) -- usada tanto por
 * {@code MfaService#confirmar} (primer enrolamiento) como por {@code MfaService#verificar}
 * (login posterior ya enrolado): en ambos casos el motivo del rechazo es el mismo, un código
 * TOTP incorrecto.
 */
public class CodigoMfaInvalidoException extends RuntimeException {
}
