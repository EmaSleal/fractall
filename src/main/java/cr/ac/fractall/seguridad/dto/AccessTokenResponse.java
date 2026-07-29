package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Respuesta de todo endpoint que emite un access token completo ya resuelto contra una
 * empresa: login directo (1 empresa), {@code seleccionar-tenant}, {@code cambiar-tenant} y
 * {@code refrescar}. {@code refreshToken} es {@code null} cuando la operación no emite uno
 * nuevo ({@code cambiar-tenant}, {@code refrescar}).
 */
@Schema(description = "Emitted when a full access token is resolved against a company")
public record AccessTokenResponse(
        @Schema(description = "Short-lived JWT (15 min)") String accessToken,
        @Schema(description = "Opaque 7-day token used to refresh the access token. Null when the operation does not issue a new one (cambiar-tenant, refrescar).", nullable = true) String refreshToken,
        @Schema(description = "Active company ID in this session") UUID empresaId) {
}
