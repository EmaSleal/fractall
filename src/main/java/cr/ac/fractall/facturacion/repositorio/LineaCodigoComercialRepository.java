package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;

public interface LineaCodigoComercialRepository extends JpaRepository<LineaCodigoComercial, UUID> {

    List<LineaCodigoComercial> findByLineaIdOrderByOrden(UUID lineaId);
}
