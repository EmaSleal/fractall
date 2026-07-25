package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.LineaDescuento;

public interface LineaDescuentoRepository extends JpaRepository<LineaDescuento, UUID> {

    List<LineaDescuento> findByLineaIdOrderByOrden(UUID lineaId);
}
