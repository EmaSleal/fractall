package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;

public interface FacturaInformacionReferenciaRepository extends JpaRepository<FacturaInformacionReferencia, UUID> {

    List<FacturaInformacionReferencia> findByFacturaIdOrderByOrden(UUID facturaId);
}
