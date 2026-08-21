package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Regla de negocio 7 (Release 2 / Fase B, ver diseño D-E): la factura de referencia de una Nota
 * de Crédito/Débito debe ser tipo {@code 01} (Factura Electrónica), no otra NC/ND. Chequeado en
 * Java ANTES de cualquier {@code INSERT} -- defensa primaria; el trigger {@code
 * trg_validar_referencia_es_factura_electronica} (V18) es defensa en profundidad, inalcanzable en
 * el camino validado normalmente (mismo principio ya documentado en el javadoc de {@code
 * FacturaService} para los triggers de exoneración de V10). Mapeada a HTTP 400 por {@code
 * GlobalExceptionHandler} (ver diseño D-G).
 */
public class ReferenciaNoEsFacturaElectronicaException extends RuntimeException {

    public ReferenciaNoEsFacturaElectronicaException(UUID facturaId, String tipoComprobanteOrigen) {
        super("La factura de referencia " + facturaId
                + " debe ser una Factura Electrónica (tipo 01), no otra Nota de Crédito/Débito (tipo "
                + tipoComprobanteOrigen + ")");
    }
}
