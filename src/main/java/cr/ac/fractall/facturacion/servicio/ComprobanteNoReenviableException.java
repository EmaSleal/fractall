package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Lanzada por {@link FacturaService#reenviar} cuando el comprobante está en un estado que
 * no permite reenvío (p. ej. GENERADO, ENVIADO, ACEPTADO) o cuando su
 * {@code xmlComprobanteReferencia} es null (el XML firmado nunca fue subido, haciendo imposible
 * el reenvío sin un nuevo ciclo de firma). Mapeada a HTTP 409 por {@code GlobalExceptionHandler}.
 *
 * <p>Package: {@code facturacion.servicio} — mismo patrón de pares servicio + excepción ya
 * establecido por {@code FacturaNoEncontradaException} y demás excepciones de dominio de este
 * paquete (no existe un paquete {@code excepcion} en este codebase).
 */
public class ComprobanteNoReenviableException extends RuntimeException {

    public ComprobanteNoReenviableException(UUID facturaId, String estado) {
        super("Comprobante no reenviable en estado " + estado + ": " + facturaId);
    }
}
