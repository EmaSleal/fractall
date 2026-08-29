package cr.ac.fractall.facturacion.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;

public interface FacturaEstadoCobroRepository extends JpaRepository<FacturaEstadoCobro, UUID> {

    /**
     * Derivado, no el findById heredado: findById resuelve por em.find() y el tratamiento del
     * discriminador @TenantId en carga por identificador no es el mismo camino que el filtro de
     * JPQL. Un metodo derivado pasa por el traductor de consultas, donde el filtro de tenant esta
     * documentado como garantizado (ver javadoc de TenantAwareEntity y de
     * ComprobanteElectronicoRepository).
     */
    Optional<FacturaEstadoCobro> findByFacturaId(UUID facturaId);
}
