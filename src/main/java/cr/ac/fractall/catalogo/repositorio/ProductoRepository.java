package cr.ac.fractall.catalogo.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.catalogo.modelo.Producto;

/**
 * {@code Producto} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity}: el filtro por
 * {@code empresa_id} (@{@code TenantId}) lo aplica Hibernate automáticamente a CUALQUIER
 * consulta emitida en esta sesión -- incluida {@link #findByCodigo(String)} -- así que no hace
 * falta (ni sería correcto) recibir {@code empresaId} como parámetro explícito aquí. Ver el
 * javadoc de {@code ClienteRepository} para la misma nota.
 */
public interface ProductoRepository extends JpaRepository<Producto, UUID> {

    Optional<Producto> findByCodigo(String codigo);
}
