package cr.ac.fractall.facturacion.repositorio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;

public interface ImpuestoLineaExoneracionRepository extends JpaRepository<ImpuestoLineaExoneracion, UUID> {

    Optional<ImpuestoLineaExoneracion> findByLineaId(UUID lineaId);
}
