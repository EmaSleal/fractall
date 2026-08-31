package cr.ac.fractall.reportes.repositorio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.facturacion.modelo.Factura;

/**
 * Cinco consultas nativas y tenant-scoped para el reporte de flujo de caja (Release 3 / Fase D,
 * ver el diseño obs #918, sección "Interfaces / Contracts"). Esta PR (2 de 7) implementa Q1, Q2,
 * Q4 y Q5 -- ventas, cobros, y los dos escalares del comparativo del período anterior. Q3
 * (cartera pendiente, {@code buscarCarteraPendienteAlCorte}) llega en la PR3 de este cambio.
 *
 * <p>Extiende el marcador {@link Repository} (sin CRUD), no {@code JpaRepository}: mismo patrón
 * de solo lectura que {@code ReporteIvaRepository}.
 *
 * <p>Nativa, no JPQL, a diferencia de {@code ReporteIvaRepository} -- ver el diseño, Decisión B9 y
 * finding 5: aquí SÍ hace falta {@code JOIN} directo entre tres tablas sin asociaciones JPA
 * ({@code factura}/{@code comprobante_electronico}/{@code cobro_factura}, todas con FK planas
 * {@code UUID}), y el {@code @TenantId} de Hibernate NO aplica a SQL nativo -- por eso
 * {@code empresa_id} se filtra explícitamente en CADA tabla de cada consulta, igual que
 * {@code FacturaRepository#buscarNativo}.
 *
 * <p>Los parámetros {@code desde}/{@code hasta} son SIEMPRE {@code LocalDateTime} obligatorios
 * (media-noche inclusiva / media-noche exclusiva del día siguiente, resuelto por el servicio),
 * nunca opcionales -- el {@code CAST(:param AS timestamp)} explícito en cada consulta sigue el
 * hábito de la casa de fijar el tipo del parámetro nativo en vez de confiar en la inferencia de
 * PostgreSQL (Decisión B9), no porque el parámetro sea opcional (esa es la razón, distinta, de por
 * qué {@code FacturaRepository#buscarNativo} usa SQL nativo en primer lugar -- ver su propio
 * javadoc).
 *
 * <p>Q1/Q2/Q4 devuelven {@code List<Object[]>}, NO proyecciones por constructor (JPQL-only) ni
 * interfaces dinámicas: Spring Data JPA no soporta constructor-expressions sobre SQL nativo. El
 * mapeo posicional a {@link FilaVentaComprobante}/{@link FilaCobroRegistrado}/
 * {@link FilaTotalPorTipoComprobante} lo hace {@code ReporteFlujoCajaService} (Fase 4 de este
 * cambio) a mano, con la misma advertencia de {@code FacturaRepository:31-32}: no confiar en el
 * nombre de columna al modificar el orden del SELECT.
 */
public interface ReporteFlujoCajaRepository extends Repository<Factura, UUID> {

    /**
     * Q1 -- filas planas de venta (Decisión B3: agregación en una sola pasada en el servicio, no
     * en SQL). Sin restricción de {@code condicion_venta} ni de {@code tipo_comprobante}
     * (Requisito "Ventas Series Includes All condicion_venta Values", D2): incluye {@code '01'}
     * contado y todo NC/ND del período, sin excepción -- el signo se resuelve en el servicio
     * (Decisión B5), nunca en esta consulta.
     *
     * <p>{@code JOIN} (no {@code LEFT JOIN}) a {@code comprobante_electronico}: toda factura
     * emitida por este sistema tiene su propio comprobante creado en la misma transacción (mismo
     * razonamiento que {@code ReporteIvaRepository}, que usa el equivalente JPQL). A diferencia de
     * Q3 (cartera, PR3), esta consulta nunca necesita tolerar la ausencia de comprobante: solo lee
     * facturas ya {@code ACEPTADO}-emitidas dentro de un período, nunca facturas de prueba
     * construidas sin ese paso.
     */
    @Query(value = """
            SELECT f.id, ce.tipo_comprobante, ce.consecutivo, ce.fecha_emision,
                   f.condicion_venta, f.cliente_id, f.moneda, f.factura_referencia_id, f.total
            FROM factura f
            JOIN comprobante_electronico ce ON ce.factura_id = f.id
            WHERE f.empresa_id = :empresaId
              AND ce.empresa_id = :empresaId
              AND ce.estado = 'ACEPTADO'
              AND ce.fecha_emision >= CAST(:desde AS timestamp)
              AND ce.fecha_emision <  CAST(:hasta AS timestamp)
            ORDER BY ce.fecha_emision, ce.consecutivo
            """, nativeQuery = true)
    List<Object[]> buscarVentasEnPeriodo(
            @Param("empresaId") UUID empresaId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    /**
     * Q2 -- filas planas de cobro (Decisión B3), agrupadas después en el servicio por
     * {@code cobro_factura.medio_pago} ÚNICAMENTE (Requisito "Cobros Series Groups by
     * cobro_factura.medio_pago Only", D6) -- {@code factura.medio_pago} nunca se lee aquí ni en
     * ninguna otra parte de este reporte.
     *
     * <p>{@code cobro_factura} carga su propio {@code empresa_id} (V23) y
     * {@code trg_validar_tenant_cobro_factura} garantiza que coincide con el de la factura, así
     * que el filtro se declara en ambos lados como defensa en profundidad, igual que el diseño lo
     * especifica.
     *
     * <p>{@code LEFT JOIN} (no {@code JOIN}) a {@code comprobante_electronico}: el consecutivo es
     * una etiqueta de exhibición, y un comprobante ausente NUNCA debe hacer desaparecer un cobro
     * real de un reporte de caja. {@code factura_id} es {@code UNIQUE} en
     * {@code comprobante_electronico} (V4), así que este LEFT JOIN no puede producir fan-out.
     */
    @Query(value = """
            SELECT cf.id, cf.fecha_cobro, cf.medio_pago, cf.monto_cobrado, cf.referencia,
                   cf.factura_id, f.condicion_venta, ce.consecutivo
            FROM cobro_factura cf
            JOIN factura f ON f.id = cf.factura_id AND f.empresa_id = :empresaId
            LEFT JOIN comprobante_electronico ce ON ce.factura_id = f.id AND ce.empresa_id = :empresaId
            WHERE cf.empresa_id = :empresaId
              AND cf.fecha_cobro >= CAST(:desde AS timestamp)
              AND cf.fecha_cobro <  CAST(:hasta AS timestamp)
            ORDER BY cf.fecha_cobro, cf.id
            """, nativeQuery = true)
    List<Object[]> buscarCobrosEnPeriodo(
            @Param("empresaId") UUID empresaId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    /**
     * Q4 -- comparativo de ventas del período anterior (Decisión B4): mismo {@code WHERE} de Q1,
     * pero agregado en SQL por {@code tipo_comprobante} en vez de traer filas planas -- el
     * comparativo nunca necesita detalle, solo escalares. El servicio aplica el mismo
     * {@code signo()} (Decisión B5) sobre cada fila de este agregado.
     */
    @Query(value = """
            SELECT ce.tipo_comprobante, CAST(SUM(f.total) AS NUMERIC(14,5)) AS total
            FROM factura f
            JOIN comprobante_electronico ce ON ce.factura_id = f.id
            WHERE f.empresa_id = :empresaId
              AND ce.empresa_id = :empresaId
              AND ce.estado = 'ACEPTADO'
              AND ce.fecha_emision >= CAST(:desde AS timestamp)
              AND ce.fecha_emision <  CAST(:hasta AS timestamp)
            GROUP BY ce.tipo_comprobante
            """, nativeQuery = true)
    List<Object[]> sumarVentasEnPeriodoPorTipo(
            @Param("empresaId") UUID empresaId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    /**
     * Q5 -- comparativo de cobros del período anterior (Decisión B4): un único escalar, sin
     * {@code JOIN} ni {@code GROUP BY} -- a diferencia de Q2, este comparativo no necesita
     * {@code condicion_venta} ni {@code consecutivo} de exhibición, así que no hace falta unir con
     * {@code factura} en absoluto.
     */
    @Query(value = """
            SELECT CAST(COALESCE(SUM(cf.monto_cobrado), 0) AS NUMERIC(14,5))
            FROM cobro_factura cf
            WHERE cf.empresa_id = :empresaId
              AND cf.fecha_cobro >= CAST(:desde AS timestamp)
              AND cf.fecha_cobro <  CAST(:hasta AS timestamp)
            """, nativeQuery = true)
    BigDecimal sumarCobrosEnPeriodo(
            @Param("empresaId") UUID empresaId,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);
}
