package cr.ac.fractall.facturacion.repositorio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.facturacion.modelo.Factura;

/**
 * {@code Factura} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity}: el filtro por
 * {@code empresa_id} (@{@code TenantId}) lo aplica Hibernate automáticamente a cualquier consulta
 * emitida en esta sesión -- ver el javadoc de {@code ClienteRepository}/{@code ProductoRepository}
 * para la misma nota.
 */
public interface FacturaRepository extends JpaRepository<Factura, UUID> {

    /**
     * Native SQL para evitar la ambigüedad de tipo de parámetro de PostgreSQL con parámetros de
     * fecha opcionales en JPQL ({@code PSQLException: could not determine data type of parameter}).
     * El {@code @TenantId} de Hibernate NO aplica automáticamente a queries nativas — el filtro de
     * empresa_id se pasa explícitamente a través de
     * {@link cr.ac.fractall.tenant.TenantContext#get()} en el servicio que llama a este método.
     *
     * <p>Proyección: la SELECT list se mapea manualmente en {@code FacturaService#mapearFila} por
     * índice posicional -- no confiar en el nombre de columna al modificar el orden aquí.
     *
     * <p>Orden: {@code f.id DESC}, no {@code f.create_date DESC} directamente. Como {@code id} es
     * UUIDv7 (ver javadoc de {@code EntidadBase}), su orden coincide con el de creación pero
     * conserva la localidad de índice del B-tree; el cursor keyset compara sobre esa misma
     * columna ({@code f.id < :cursor}) para que la dirección de paginación siga siendo consistente
     * con el ORDER BY.
     */
    @Query(value = """
            SELECT f.id, ce.consecutivo, ce.tipo_comprobante, f.cliente_id, cl.nombre,
                   ce.ambiente_hacienda, f.moneda, f.total,
                   ce.estado, ce.ultimo_resultado_consulta, ce.fecha_emision
            FROM factura f
              LEFT JOIN comprobante_electronico ce ON ce.factura_id = f.id AND ce.empresa_id = :empresaId
              LEFT JOIN cliente cl ON cl.id = f.cliente_id AND cl.empresa_id = :empresaId
            WHERE f.empresa_id = :empresaId
              AND (:clienteId IS NULL OR f.cliente_id = CAST(:clienteId AS uuid))
              AND (CAST(:desde AS date) IS NULL OR CAST(f.create_date AS date) >= CAST(:desde AS date))
              AND (CAST(:hasta AS date) IS NULL OR CAST(f.create_date AS date) <= CAST(:hasta AS date))
              AND (:estado IS NULL OR ce.estado = :estado)
              AND (:tipoComprobante IS NULL OR ce.tipo_comprobante = :tipoComprobante)
              AND (:cursor IS NULL OR f.id < CAST(:cursor AS uuid))
            ORDER BY f.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> buscarNativo(
            @Param("empresaId") UUID empresaId,
            @Param("cursor") String cursor,
            @Param("clienteId") String clienteId,
            @Param("desde") String desde,
            @Param("hasta") String hasta,
            @Param("estado") String estado,
            @Param("tipoComprobante") String tipoComprobante,
            @Param("limit") int limit);

    /**
     * Nativa por el mismo motivo que {@link #buscarNativo} -- el {@code @TenantId} de Hibernate
     * NO aplica a queries nativas, así que {@code empresaId} se filtra explícitamente aquí en
     * vez de confiar en que el llamador ya validó el tenant de {@code facturaId} por otra vía.
     */
    @Query(value = "SELECT cl.nombre FROM factura f JOIN cliente cl ON cl.id = f.cliente_id "
            + "WHERE f.id = :facturaId AND f.empresa_id = :empresaId", nativeQuery = true)
    Optional<String> findClienteNombreByFacturaId(
            @Param("facturaId") UUID facturaId, @Param("empresaId") UUID empresaId);

    /**
     * Regla de negocio 3 (Release 2 / Fase B): suma de {@code factura.total} de todas las Notas
     * de Crédito (tipo_comprobante='03') ya emitidas contra la factura origen indicada -- misma
     * agregación que hace el trigger {@code fn_validar_tope_nota_credito} (V18), pre-calculada
     * aquí en Java por {@code NotaCreditoDebitoService} ANTES de intentar el {@code INSERT} (el
     * trigger queda como defensa en profundidad para la carrera de concurrencia, ver su javadoc
     * en V18 y {@code GlobalExceptionHandler#manejarErrorSqlNoCategorizado}). Nativa (no
     * JPQL/Criteria) porque agrega sobre dos tablas vía JOIN -- mismo motivo que {@link
     * #buscarNativo}; {@code empresaId} se filtra explícitamente por el mismo principio (el
     * {@code @TenantId} de Hibernate no aplica a SQL nativo).
     */
    @Query(value = "SELECT COALESCE(SUM(f.total), 0) FROM factura f "
            + "JOIN comprobante_electronico ce ON ce.factura_id = f.id "
            + "WHERE ce.tipo_comprobante = '03' AND f.factura_referencia_id = :facturaOrigenId "
            + "AND f.empresa_id = :empresaId", nativeQuery = true)
    BigDecimal sumarTotalNotasCreditoPorFacturaOrigen(
            @Param("facturaOrigenId") UUID facturaOrigenId, @Param("empresaId") UUID empresaId);
}
