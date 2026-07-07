package cr.ac.fractall.facturacion.repositorio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.Factura;

/**
 * {@code Factura} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity}: el filtro por
 * {@code empresa_id} (@{@code TenantId}) lo aplica Hibernate automáticamente a cualquier consulta
 * emitida en esta sesión -- ver el javadoc de {@code ClienteRepository}/{@code ProductoRepository}
 * para la misma nota.
 */
public interface FacturaRepository extends JpaRepository<Factura, UUID> {
}
