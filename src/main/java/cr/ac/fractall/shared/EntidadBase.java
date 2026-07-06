package cr.ac.fractall.shared;

import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Superclase para toda entidad persistente, tenant-scoped o no.
 *
 * <p>Mapea {@code id} como UUIDv7 generado por Postgres ({@code DEFAULT uuidv7()}
 * en cada tabla), no por Hibernate. La columna se marca {@code insertable = false}
 * para que Hibernate omita el valor en el INSERT y deje aplicar el default de la
 * base de datos, y se anota con {@link Generated} sobre {@link EventType#INSERT}
 * para que Hibernate relea el valor generado inmediatamente después de insertar.
 * Esto preserva la localidad de índice de UUIDv7 (friendly con B-tree), que un
 * {@code GenerationType.UUID} generado en Java (UUIDv4 aleatorio) destruiría.
 *
 * <p>Ver sección 5.1 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@MappedSuperclass
public abstract class EntidadBase {

    @Id
    @Generated(event = EventType.INSERT)
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    public UUID getId() {
        return id;
    }
}
