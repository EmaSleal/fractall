package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;

public interface FacturaMedioPagoRepository extends JpaRepository<FacturaMedioPago, UUID> {

    List<FacturaMedioPago> findByFacturaIdOrderByOrden(UUID facturaId);
}
