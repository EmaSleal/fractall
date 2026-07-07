package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * La {@code cliente_exoneracion} referenciada por una línea de factura existe y es del tenant
 * correcto, pero su {@code cliente_id} no coincide con el {@code cliente_id} de la factura que
 * la está usando (sección 4.15.2 de {@code arquitectura-facturacion-electronica-cr.md}) --
 * rechazado aquí, en Java, ANTES de persistir (ver el javadoc de {@code FacturaService} sobre por
 * qué no basta con el trigger de motor {@code fn_validar_mismo_tenant}).
 */
public class ExoneracionNoPerteneceAlClienteException extends RuntimeException {

    public ExoneracionNoPerteneceAlClienteException(UUID exoneracionId, UUID clienteId) {
        super("La exoneración %s no pertenece al cliente %s de esta factura".formatted(exoneracionId, clienteId));
    }
}
