package cr.ac.fractall.facturacion.repositorio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;

/**
 * {@code ComprobanteElectronico} extiende {@link cr.ac.fractall.tenant.TenantAwareEntity} --
 * mismo filtrado automático por {@code empresa_id} que {@code FacturaRepository}.
 */
public interface ComprobanteElectronicoRepository extends JpaRepository<ComprobanteElectronico, UUID> {

    /**
     * JPQL derivado -- SÍ pasa por el traductor de consultas de Hibernate, así que el filtro
     * automático de {@code @TenantId} (ver el javadoc de
     * {@link cr.ac.fractall.tenant.TenantAwareEntity}) se aplica con normalidad: solo devuelve
     * filas de la empresa actualmente resuelta en {@link cr.ac.fractall.tenant.TenantContext}.
     * Usado por {@code ComprobanteHaciendaPollingScheduledJob} DESPUÉS de fijar el tenant real de
     * cada iteración -- ver el javadoc de ese job y de {@link #findEmpresaIdsConEstado}.
     */
    List<ComprobanteElectronico> findByEstado(String estado);

    /**
     * Consulta NATIVA (no JPQL/Criteria) -- deliberadamente fuera del traductor de consultas de
     * Hibernate, que es el único lugar donde el filtro automático de {@code @TenantId} se aplica
     * (el javadoc de {@link cr.ac.fractall.tenant.TenantAwareEntity} lo dice explícitamente:
     * "Hibernate lo aplica automáticamente como filtro en JPQL/Criteria" -- nunca a SQL nativo).
     * Por eso este método SÍ puede escanear {@code comprobante_electronico} a través de TODAS las
     * empresas a la vez, algo que ninguna consulta JPQL/Criteria sobre esta entidad puede hacer
     * legítimamente.
     *
     * <p>Este es un escape hatch NUEVO en este codebase -- ningún job programado anterior cruza
     * tenants sobre una entidad {@code @TenantId} ({@code EmailReintentoScheduledJob} opera sobre
     * {@code ColaReintentoEmail}, que no tiene esa columna). {@code ComprobanteHaciendaPollingScheduledJob}
     * lo usa para DESCUBRIR qué empresas tienen comprobantes pendientes de confirmar en Hacienda,
     * sin conocer de antemano ningún {@code empresaId} -- imposible de lograr con JPQL/Criteria
     * normal, que siempre exige un tenant YA resuelto en {@code TenantContext} antes de poder
     * ejecutar cualquier consulta ({@code EmpresaTenantIdentifierResolver} falla cerrado, ver su
     * javadoc).
     *
     * <p>Sigue siendo seguro invocar este método bajo un
     * {@code TenantContextDescartable#ejecutar} con un UUID aleatorio de descarte -- necesario
     * solo para satisfacer ese chequeo fail-closed al ABRIR el {@code EntityManager} (ver el
     * javadoc de {@code TenantContextDescartable}): a diferencia de {@link #findByEstado}, el
     * {@code WHERE} de esta consulta nativa nunca depende de {@code @TenantId} ni del tenant en
     * contexto, así que ese valor de descarte no puede filtrar ni contaminar el resultado. Una vez
     * identificadas las empresas, TODO el trabajo posterior por empresa debe volver a fijar
     * {@code TenantContext} al {@code empresaId} REAL de esa iteración antes de usar
     * {@link #findByEstado} o cualquier otra consulta JPQL sobre esta entidad -- usar el valor de
     * descarte ahí sí sería inseguro (ver el javadoc de {@code TenantContextDescartable} sobre por
     * qué solo aplica a entidades sin {@code @TenantId}).
     */
    @Query(value = "SELECT DISTINCT empresa_id FROM comprobante_electronico WHERE estado = :estado", nativeQuery = true)
    List<UUID> findEmpresaIdsConEstado(@Param("estado") String estado);
}
