package cr.ac.fractall.notificaciones.servicio;

/**
 * Adjunto para el método {@link ResendEmailClient#enviar(String, String, String, java.util.List)}:
 * un archivo identificado por su nombre y sus bytes en texto claro (no cifrados -- el llamador
 * es responsable de descifrar antes de construir este record).
 *
 * <p>Agregado en Fase 9 para el flujo de entrega al cliente (PDF + XMLs del comprobante).
 */
public record Adjunto(String filename, byte[] content) {
}
