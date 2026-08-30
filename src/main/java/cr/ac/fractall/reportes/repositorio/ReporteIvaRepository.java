package cr.ac.fractall.reportes.repositorio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;

/**
 * Fetch Q1 del reporte de IVA (Release 3 / Fase D, ver el diseño, decisión A2/A3): un único theta-
 * join JPQL de 3 entidades ({@code ComprobanteElectronico × Factura × LineaFactura}) por período de
 * emisión propio y estado ACEPTADO.
 *
 * <p>Extiende el marcador {@link Repository} (sin CRUD), no {@code JpaRepository}: este repositorio
 * es de solo lectura y expone únicamente esta consulta. Extiende marker interface {@code Repository}
 * en vez de heredar métodos de escritura que el reporte nunca usa.
 *
 * <p>JPQL, no SQL nativo, a diferencia del precedente de {@code FacturaRepository} (JOINs sobre
 * múltiples tablas van nativos ahí) -- ver el diseño, decisión A3: esa excepción de la casa existe
 * por parámetros de fecha OPCIONALES, que rompen la inferencia de tipo de PostgreSQL
 * ({@code could not determine data type of parameter}). Los parámetros {@code desde}/{@code hasta}
 * de este reporte son OBLIGATORIOS, así que ese bloqueador no aplica, y JPQL mantiene el
 * {@code @TenantId} de Hibernate automático sobre las 3 entidades sin tener que enhebrar
 * {@code empresa_id} a mano.
 */
public interface ReporteIvaRepository extends Repository<ComprobanteElectronico, UUID> {

    @Query("""
        select new cr.ac.fractall.reportes.repositorio.FilaLineaComprobante(
            c.id, c.tipoComprobante, c.consecutivo, c.claveNumerica, c.fechaEmision,
            f.id, f.clienteId, f.moneda, f.facturaReferenciaId,
            l.id, l.numeroLinea, l.subtotal,
            l.gravadoAplicado, l.porcentajeImpuestoAplicado,
            l.exoneracionId, l.montoExoneracionAplicado)
        from ComprobanteElectronico c, Factura f, LineaFactura l
        where f.id = c.facturaId
          and l.facturaId = f.id
          and c.estado = :estado
          and c.fechaEmision >= :desde
          and c.fechaEmision < :hasta
        order by c.fechaEmision, c.consecutivo, l.numeroLinea
        """)
    List<FilaLineaComprobante> buscarLineasEnPeriodo(
            @Param("estado") String estado,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);
}
