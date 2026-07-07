package cr.ac.fractall.catalogo.repositorio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;

/**
 * Filtrado automático por {@code empresa_id} vía {@code @TenantId} -- ver el javadoc de
 * {@code ProductoRepository}/{@code ClienteRepository} para la misma nota.
 */
public interface ClienteExoneracionRepository extends JpaRepository<ClienteExoneracion, UUID> {

    List<ClienteExoneracion> findByClienteId(UUID clienteId);

    /**
     * Soporta el pre-chequeo de duplicados de {@code ClienteExoneracionService#crear} contra
     * {@code UNIQUE(cliente_id, numero_documento)} (ver {@code V8__cliente_exoneracion.sql}) --
     * mismo patrón que {@code ClienteRepository#findByNumeroIdentificacion}/
     * {@code ProductoRepository#findByCodigo}.
     */
    Optional<ClienteExoneracion> findByClienteIdAndNumeroDocumento(UUID clienteId, String numeroDocumento);
}
