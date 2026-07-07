package cr.ac.fractall.hacienda.dto;

/**
 * Respuesta de autenticación OAuth2 de Hacienda ({@code access_token}/{@code refresh_token}).
 *
 * <p>Portado (Categoría A) de {@code HaciendaApiService.TokenResponse}, un {@code record} anidado
 * en la interfaz original -- se extrae a un archivo propio en {@code dto} para seguir la
 * convención ya establecida por el resto del módulo {@code cr.ac.fractall.hacienda} (ver
 * {@code HaciendaConsultaDTO} y compañía), en vez de anidarlo dentro de
 * {@link cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService}.
 */
public record TokenHaciendaDTO(
        String accessToken,
        String refreshToken,
        Integer expiresIn,
        String tokenType) {
}
