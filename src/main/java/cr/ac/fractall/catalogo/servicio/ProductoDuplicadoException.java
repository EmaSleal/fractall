package cr.ac.fractall.catalogo.servicio;

/**
 * Ya existe un {@code Producto} con este {@code codigo} para el tenant actual --
 * {@code UNIQUE(empresa_id, codigo)}. Rechazo explícito ANTES de {@code saveAndFlush}, nunca una
 * {@code DataIntegrityViolationException} sin capturar surgiendo como 500 -- mismo principio de
 * la lección de la Fase 5 (bug de {@code credencial_hacienda} con doble INSERT) aplicado aquí de
 * forma proactiva, aunque el brief de esta fase no lo pidiera explícitamente para
 * {@code producto}.
 */
public class ProductoDuplicadoException extends RuntimeException {

    public ProductoDuplicadoException(String codigo) {
        super("Ya existe un producto con el código '" + codigo + "' para esta empresa");
    }
}
