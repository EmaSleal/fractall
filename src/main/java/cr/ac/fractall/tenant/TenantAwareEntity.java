package cr.ac.fractall.tenant;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Superclase para toda entidad de negocio con alcance de tenant (empresa).
 *
 * <p>Mapea {@code empresa_id} como discriminador de tenant vía {@link TenantId},
 * lo que hace que Hibernate lo aplique automáticamente como filtro en JPQL/Criteria
 * sin que cada entidad deba declararlo ni recordarlo por su cuenta.
 *
 * <p>Ver sección 5.1 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@MappedSuperclass
public abstract class TenantAwareEntity extends EntidadBase {

    @TenantId
    @Column(name = "empresa_id", nullable = false, updatable = false)
    private UUID empresaId;

    public UUID getEmpresaId() {
        return empresaId;
    }
}
