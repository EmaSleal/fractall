package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.LineaFactura;

/**
 * {@code LineaFactura} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity} -- mismo
 * filtrado automático por {@code empresa_id} que {@code FacturaRepository}.
 */
public interface LineaFacturaRepository extends JpaRepository<LineaFactura, UUID> {

    /**
     * Soporta {@code XmlFacturaGeneratorService#generarXmlFactura} (Fase 8): las líneas deben
     * recorrerse en el mismo orden en que fueron numeradas al crear la factura (sección 4.13 de
     * {@code arquitectura-facturacion-electronica-cr.md}), no en orden de inserción arbitrario.
     */
    List<LineaFactura> findByFacturaIdOrderByNumeroLinea(UUID facturaId);
}
