package cr.ac.fractall.catalogo.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Catálogo de distritos de Costa Rica (V15__catalogo_ubicacion_cr.sql).
 *
 * <p>Sin {@code id} sustituto: la llave natural ({@link DistritoId}) ES la PK. Sin relación JPA
 * hacia {@code Cliente}/{@code Empresa} -- ver el javadoc de {@link Canton} para la convención de
 * clave foránea plana que también aplica aquí. {@link cr.ac.fractall.catalogo.repositorio.DistritoRepository}
 * expone el método de existencia real que usa {@code UbicacionValidator}.
 */
@Entity
@Table(name = "distrito")
@Getter
@Setter
@NoArgsConstructor
public class Distrito {

    @EmbeddedId
    private DistritoId id;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;
}
