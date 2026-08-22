package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Una línea intenta aplicar una exoneración vía el path legacy de {@code exoneracionId}, pero el
 * documento no tiene cliente ({@code cliente == null}) -- caso posible desde Release 2 / Fase C,
 * un Tiquete Electrónico sin receptor identificado (venta de mostrador). Las exoneraciones
 * pertenecen siempre a un cliente ({@code cliente_exoneracion.cliente_id}), así que sin cliente no
 * hay contra qué validar pertenencia (sección 4.15.2) -- se rechaza aquí, en Java, ANTES de
 * persistir, con un error de dominio explícito en vez de dejar que
 * {@link LineaFacturaEnsamblador#aplicarExoneracion} reviente con un
 * {@code NullPointerException} crudo al intentar leer {@code cliente.getId()}.
 */
public class ExoneracionRequiereClienteException extends RuntimeException {

    public ExoneracionRequiereClienteException(UUID exoneracionId) {
        super("La exoneración %s no puede aplicarse: el documento no tiene cliente identificado"
                .formatted(exoneracionId));
    }
}
