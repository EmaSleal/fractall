package cr.ac.fractall.seguridad.dto;

/**
 * Respuesta de {@code POST /auth/mfa/enrolar}: {@code qrCodeBase64Png} para escanear con la
 * app autenticadora, y {@code secretoBase32} como respaldo de entrada manual (estándar en
 * apps autenticadoras cuando escanear el QR no es posible).
 */
public record MfaEnrolamientoResponse(String qrCodeBase64Png, String secretoBase32) {
}
