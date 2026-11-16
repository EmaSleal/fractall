package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Lanzada por {@link FacturaService#reenviar} cuando el comprobante está en un estado que
 * no permite reenvío (todo lo que no sea FIRMADO o ERROR, ADR-055: p. ej. GENERADO, ENVIADO,
 * RECHAZADO, ACEPTADO), cuando su {@code xmlComprobanteReferencia} es null (el XML firmado nunca
 * fue subido, haciendo imposible el reenvío sin un nuevo ciclo de firma) o cuando el reclamo
 * atómico no afectó ninguna fila porque ya hay un reenvío manual en curso (ADR-056). Mapeada a
 * HTTP 409 por {@code GlobalExceptionHandler}.
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
