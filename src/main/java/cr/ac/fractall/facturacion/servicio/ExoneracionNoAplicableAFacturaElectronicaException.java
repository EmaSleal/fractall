package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * El {@code tipoDocumento} de la {@code cliente_exoneracion} referenciada (catálogo oficial de
 * 12 códigos, sección 4.15.1) es uno de los 4 códigos ({@code 01}/{@code 05}/{@code 06}/
 * {@code 07}) exclusivos de Nota de Crédito/Débito -- no aplican a Factura Electrónica (tipo
 * {@code 01} de comprobante, única en alcance de Release 1, sección 8.1). Rechazado aquí, en
 * Java, ANTES de persistir -- ver el javadoc de {@code FacturaService}.
 */
public class ExoneracionNoAplicableAFacturaElectronicaException extends RuntimeException {

    public ExoneracionNoAplicableAFacturaElectronicaException(UUID exoneracionId, String tipoDocumento) {
        super(("El tipo de exoneración %s (id %s) es exclusivo de Nota de Crédito/Débito, "
                + "no aplica a Factura Electrónica").formatted(tipoDocumento, exoneracionId));
    }
}
