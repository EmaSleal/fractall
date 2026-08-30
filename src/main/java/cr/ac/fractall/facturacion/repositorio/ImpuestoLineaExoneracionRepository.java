package cr.ac.fractall.facturacion.repositorio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;

public interface ImpuestoLineaExoneracionRepository extends JpaRepository<ImpuestoLineaExoneracion, UUID> {

    Optional<ImpuestoLineaExoneracion> findByLineaId(UUID lineaId);

    /**
     * Lookup batcheado para el reporte de IVA (Release 3 / Fase D) y para
     * {@code CalculadoraImpuestoLineaReconciliacionIT}: evita una consulta por línea al construir
     * el mapa {@code lineaId -> ImpuestoLineaExoneracion} que alimenta
     * {@code CalculadoraImpuestoLinea#calcular}. El llamador es responsable de trocear el
     * {@code Collection<UUID>} en bloques de 1000 si el volumen de líneas del período lo amerita
     * (ver el diseño, sección "Fetch strategy").
     */
    List<ImpuestoLineaExoneracion> findByLineaIdIn(Collection<UUID> lineaIds);
}
