package cr.ac.fractall.facturacion.repositorio;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.facturacion.modelo.CobroFactura;

/**
 * {@code CobroFactura} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity}: el filtro por
 * {@code empresa_id} (@{@code TenantId}) lo aplica Hibernate automaticamente a cualquier consulta
 * JPQL/derivada emitida en esta sesion.
 */
public interface CobroFacturaRepository extends JpaRepository<CobroFactura, UUID> {

    /**
     * Historial para reconciliacion. Desempate por id porque dos cobros pueden compartir
     * fecha_cobro; id es UUIDv7 (EntidadBase), asi que su orden coincide con el de insercion.
     * Metodo derivado => el filtro @TenantId aplica automaticamente.
     */
    List<CobroFactura> findByFacturaIdOrderByFechaCobroAscIdAsc(UUID facturaId);

    /**
     * Pre-chequeo en Java del acumulado. JPQL, no nativa, precisamente para que @TenantId siga
     * aplicando -- a diferencia de
     * FacturaRepository#sumarTotalNotasCreditoAceptadasPorFacturaOrigen, que es nativa y filtra
     * empresa_id a mano.
     */
    @Query("SELECT COALESCE(SUM(c.montoCobrado), 0) FROM CobroFactura c WHERE c.facturaId = :facturaId")
    BigDecimal sumarMontoCobradoPorFactura(@Param("facturaId") UUID facturaId);
}
