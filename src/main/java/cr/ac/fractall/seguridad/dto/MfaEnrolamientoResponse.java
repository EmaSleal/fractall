package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta de {@code POST /auth/mfa/enrolar}: {@code qrCodeBase64Png} para escanear con la
 * app autenticadora, y {@code secretoBase32} como respaldo de entrada manual (estándar en
 * apps autenticadoras cuando escanear el QR no es posible).
 */
@Schema(description = "Data needed to enroll a TOTP authenticator app")
public record MfaEnrolamientoResponse(
        @Schema(description = "QR code as a Base64-encoded PNG image; scan with the authenticator app") String qrCodeBase64Png,
        @Schema(description = "TOTP secret in Base32 format; for manual entry when the QR cannot be scanned") String secretoBase32) {
}
