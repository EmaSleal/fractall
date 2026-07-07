package cr.ac.fractall.catalogo.servicio;

/**
 * El bloque de ubicación de {@code cliente} (provincia/cantón/distrito/otras señas) no cumple
 * la regla "todo o nada" que también aplica el {@code CHECK} de motor (sección 4.11 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- se valida explícitamente en esta capa
 * ANTES de {@code saveAndFlush} para nunca dejar que una {@code DataIntegrityViolationException}
 * sin capturar llegue como 500 al llamador (misma clase de bug que la revisión de la Fase 5 ya
 * corrigió una vez).
 */
public class UbicacionInvalidaException extends RuntimeException {

    public UbicacionInvalidaException(String mensaje) {
        super(mensaje);
    }
}
