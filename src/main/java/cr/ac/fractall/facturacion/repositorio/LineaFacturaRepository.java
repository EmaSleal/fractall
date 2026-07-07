package cr.ac.fractall.facturacion.repositorio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.LineaFactura;

/**
 * {@code LineaFactura} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity} -- mismo
 * filtrado automático por {@code empresa_id} que {@code FacturaRepository}.
 */
public interface LineaFacturaRepository extends JpaRepository<LineaFactura, UUID> {
}
