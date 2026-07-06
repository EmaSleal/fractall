package cr.ac.fractall.tenant;

import java.util.Map;
import java.util.UUID;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import cr.ac.fractall.shared.TenantNoResueltoException;

/**
 * Conecta {@link TenantContext} con el mecanismo de multi-tenancy de Hibernate.
 *
 * <p>Fail-closed, no fail-open: sin {@code empresa_id} en contexto, la consulta
 * se bloquea en lugar de resolver a un valor por defecto que podría filtrar
 * datos entre tenants.
 *
 * <p>Ver sección 5.3 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@Component
public class EmpresaTenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID empresaId = TenantContext.get();
        if (empresaId == null) {
            throw new TenantNoResueltoException(
                    "Operación bloqueada: no hay empresa_id en contexto de ejecución");
        }
        return empresaId;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
