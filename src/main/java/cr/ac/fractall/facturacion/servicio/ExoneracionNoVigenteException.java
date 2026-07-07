package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * La {@code cliente_exoneracion} referenciada existe y pertenece al cliente correcto, pero no
 * está vigente -- inactiva o vencida, según {@code ClienteExoneracionService#estaVigente}
 * (Fase 6). Rechazado aquí, en Java, ANTES de persistir -- ver el javadoc de
 * {@code FacturaService}.
 */
public class ExoneracionNoVigenteException extends RuntimeException {

    public ExoneracionNoVigenteException(UUID exoneracionId) {
        super("La exoneración %s no está vigente (inactiva o vencida)".formatted(exoneracionId));
    }
}
