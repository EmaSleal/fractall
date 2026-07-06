package cr.ac.fractall.seguridad.servicio;

/**
 * Resultado de {@code SesionService#seleccionarTenant}: exactamente uno de los dos pares de
 * campos está poblado, según {@code requiereMfa} -- mismo patrón simple (sin jerarquía
 * sellada) que {@code LoginService.LoginResultado}, por el mismo motivo (consistencia con el
 * resto de resultados de servicio del paquete).
 *
 * <p>{@code cambiarTenant} y {@code refrescar} NO usan este tipo: siguen devolviendo
 * {@code TokensAcceso} directamente porque el batch de MFA solo exige la puerta en el login y
 * en la selección de tenant iniciales (sección 3.3) -- una sesión que ya pasó esa puerta una
 * vez no vuelve a pasar por ella al cambiar de empresa o refrescar el access token.
 */
public record SesionResultado(boolean requiereMfa, TokensAcceso tokens,
        String tokenMfaPendiente, boolean mfaRequiereEnrolamiento) {

    static SesionResultado completo(TokensAcceso tokens) {
        return new SesionResultado(false, tokens, null, false);
    }

    static SesionResultado requiereMfa(String tokenMfaPendiente, boolean mfaRequiereEnrolamiento) {
        return new SesionResultado(true, null, tokenMfaPendiente, mfaRequiereEnrolamiento);
    }
}
