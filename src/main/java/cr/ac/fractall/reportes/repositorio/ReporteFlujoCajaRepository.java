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
 * ver el diseño obs #918, sección "Interfaces / Contracts"). La PR2 (2 de 7) implementó Q1, Q2, Q4
 * y Q5 -- ventas, cobros, y los dos escalares del comparativo del período anterior. Esta PR (3 de
 * 7) agrega Q3 (cartera pendiente, {@code buscarCarteraPendienteAlCorte}) -- la consulta de mayor
 * complejidad de este cambio, ver su propio javadoc.
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

    /**
     * Q3 -- cartera pendiente punto-en-el-tiempo, una fila por factura (Decisión B1, Requisito
     * "Point-in-Time Cartera Pendiente With Three Date Bounds"). Traslada la topología de DOS
     * {@code CROSS JOIN LATERAL} independientes de {@code factura_estado_cobro} (ver V23/V24) en
     * vez de un {@code JOIN + GROUP BY}: con dos relaciones uno-a-muchos (cobros Y notas de
     * crédito) un producto cartesiano duplicaría filas y ambos {@code SUM} contarían de más.
     *
     * <p><b>Tres cotas independientes</b>, cada una en su propia sub-consulta o filtro, nunca
     * mezcladas entre sí:
     * <ul>
     *   <li>Cota (1) -- {@code cobro_factura.fecha_cobro < :corteExclusivo}, dentro del LATERAL de
     *       cobros: un cobro posterior al corte no se netea.
     *   <li>Cota (2) -- {@code nc_ce.fecha_emision < :corteExclusivo}, dentro del LATERAL de notas
     *       de crédito: una NC aceptada emitida después del corte no se netea.
     *   <li>Cota (3) -- {@code ce.fecha_emision < :corteExclusivo}, en el {@code WHERE} externo: una
     *       factura emitida después del corte se excluye POR COMPLETO, sin importar su saldo.
     * </ul>
     *
     * <p>Forma medio-abierta ({@code <}, nunca {@code <=}) sobre un parámetro
     * {@code corteExclusivo = fechaCorte.plusDays(1).atStartOfDay()} resuelto por el servicio
     * (Decisión B9, finding 5): comparar directamente {@code <= fechaCorte} con un
     * {@code TIMESTAMP} excluiría silenciosamente todo lo ocurrido DURANTE el día del corte, porque
     * PostgreSQL ensancha una fecha a media-noche.
     *
     * <p>Base set restringido a {@code f.condicion_venta IN ('02','03','04')} (excluye contado,
     * D2/B1) y {@code ce.tipo_comprobante IN ('01','04')} (Factura o Tiquete -- nunca la propia NC/
     * ND, que hereda {@code condicion_venta} de su origen, ver diseño finding 1) Y
     * {@code ce.estado = 'ACEPTADO'}.
     *
     * <p><b>Divergencia deliberada vs. la vista {@code factura_estado_cobro}</b> (finding 3 del
     * diseño): la vista NUNCA filtró {@code estado = 'ACEPTADO'} sobre la factura base, ni antes ni
     * después de V24 -- esta consulta SÍ lo exige explícitamente, porque la cota (3) necesita
     * {@code ce.fecha_emision} de todos modos (solo existe vía este join) y una factura sin
     * comprobante aceptado no debería contarse como cartera cobrable. Esta consulta usa
     * {@code LEFT JOIN} (no {@code JOIN}) a {@code comprobante_electronico} con el mismo chequeo
     * tolerante a NULL que V24 introdujo en la vista ({@code ce.tipo_comprobante IS NULL OR
     * ce.tipo_comprobante IN ('01','04')}) para replicar exactamente su topología de join -- pero,
     * a diferencia de la vista, esta consulta mantiene {@code ce.estado = 'ACEPTADO'} y la cota (3)
     * como filtros ESTRICTOS (no tolerantes a NULL): una factura sin ningún
     * {@code comprobante_electronico} tiene ambos valores NULL, así que estos dos filtros ya la
     * excluyen por sí solos, dejando el comportamiento neto idéntico al de un {@code JOIN} interno
     * para ese caso, sin depender de que esa premisa (finding 9) sea universalmente cierta en datos
     * de prueba como lo demostró el gate de no-regresión de la PR1 (V24).
     *
     * <p>{@code nc_ce.tipo_comprobante = '03'} es indispensable en el LATERAL de notas de crédito:
     * {@code factura_referencia_id} también se puebla para notas de débito ('04' en
     * {@code tipo_comprobante}, no confundir con el '04' de Tiquete en {@code ce.tipo_comprobante}
     * del base set -- son catálogos distintos), y una ND aceptada NUNCA debe restar (misma
     * razón que {@code FacturaRepository}).
     *
     * <p>{@code saldo_pendiente} NO se redondea a piso -- ver el javadoc de
     * {@link FilaCarteraFactura}.
     */
    @Query(value = """
            SELECT base.factura_id,
                   base.consecutivo,
                   base.total,
                   base.total_nota_credito,
                   base.total_neto,
                   base.total_cobrado,
                   CAST(base.total_neto - base.total_cobrado AS NUMERIC(14,5)) AS saldo_pendiente
            FROM (
                SELECT f.id                                         AS factura_id,
                       ce.consecutivo                               AS consecutivo,
                       f.total                                      AS total,
                       nc.total_nc                                  AS total_nota_credito,
                       CAST(f.total - nc.total_nc AS NUMERIC(14,5)) AS total_neto,
                       c.total_cobrado                              AS total_cobrado
                FROM factura f
                LEFT JOIN comprobante_electronico ce ON ce.factura_id = f.id
                CROSS JOIN LATERAL (
                    SELECT CAST(COALESCE(SUM(nc2.total), 0) AS NUMERIC(14,5)) AS total_nc
                    FROM factura nc2
                    JOIN comprobante_electronico nc_ce ON nc_ce.factura_id = nc2.id
                    WHERE nc2.factura_referencia_id = f.id
                      AND nc2.empresa_id  = :empresaId
                      AND nc_ce.empresa_id = :empresaId
                      AND nc_ce.tipo_comprobante = '03'
                      AND nc_ce.estado = 'ACEPTADO'
                      AND nc_ce.fecha_emision < CAST(:corteExclusivo AS timestamp)
                ) nc
                CROSS JOIN LATERAL (
                    SELECT CAST(COALESCE(SUM(cf.monto_cobrado), 0) AS NUMERIC(14,5)) AS total_cobrado
                    FROM cobro_factura cf
                    WHERE cf.factura_id = f.id
                      AND cf.empresa_id = :empresaId
                      AND cf.fecha_cobro < CAST(:corteExclusivo AS timestamp)
                ) c
                WHERE f.empresa_id  = :empresaId
                  AND (ce.empresa_id IS NULL OR ce.empresa_id = :empresaId)
                  AND f.condicion_venta IN ('02', '03', '04')
                  AND (ce.tipo_comprobante IS NULL OR ce.tipo_comprobante IN ('01', '04'))
                  AND ce.estado = 'ACEPTADO'
                  AND ce.fecha_emision < CAST(:corteExclusivo AS timestamp)
            ) base
            ORDER BY base.factura_id
            """, nativeQuery = true)
    List<Object[]> buscarCarteraPendienteAlCorte(
            @Param("empresaId") UUID empresaId,
            @Param("corteExclusivo") LocalDateTime corteExclusivo);
}
