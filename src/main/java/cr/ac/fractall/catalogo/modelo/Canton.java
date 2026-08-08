package cr.ac.fractall.catalogo.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Catálogo de cantones de Costa Rica (V15__catalogo_ubicacion_cr.sql).
 *
 * <p>Sin {@code id} sustituto: la llave natural ({@link CantonId}) ES la PK. Sin relación JPA
 * hacia {@link Provincia} -- la FK vive a nivel de motor (ver la migración), pero esta entidad
 * solo guarda la clave foránea plana dentro de {@code CantonId#provinciaCodigo}, misma
 * convención que {@code Cliente}/{@code Empresa} guardan {@code canton}/{@code distrito} como
 * {@code String} plano en vez de una relación {@code @ManyToOne}.
 */
@Entity
@Table(name = "canton")
@Getter
@Setter
@NoArgsConstructor
public class Canton {

    @EmbeddedId
    private CantonId id;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;
}
