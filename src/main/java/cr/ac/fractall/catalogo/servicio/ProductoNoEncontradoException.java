package cr.ac.fractall.catalogo.servicio;

import java.util.UUID;

/**
 * Ningún {@code Producto} con este id existe para el tenant actual -- incluye tanto "no existe"
 * como "existe mas pertenece a otra empresa" (un id de otro tenant resuelve vacío vía
 * {@code ProductoRepository#findById}, filtrado automáticamente por {@code @TenantId}; ver el
 * javadoc de {@code ProductoRepository}).
 */
public class ProductoNoEncontradoException extends RuntimeException {

    public ProductoNoEncontradoException(UUID id) {
        super("Producto no encontrado: " + id);
    }
}
