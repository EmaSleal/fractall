package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;

public interface FacturaOtrosCargosRepository extends JpaRepository<FacturaOtrosCargos, UUID> {

    List<FacturaOtrosCargos> findByFacturaIdOrderByOrden(UUID facturaId);
}
