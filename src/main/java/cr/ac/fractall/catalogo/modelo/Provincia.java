package cr.ac.fractall.catalogo.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Catálogo de provincias de Costa Rica (V15__catalogo_ubicacion_cr.sql).
 *
 * <p>Sin {@code id} sustituto: la clave natural ({@code codigo}) ES la PK, así que insertar dos
 * veces la misma provincia es estructuralmente imposible, sin importar cuántas veces se re-corra
 * la migración o un script de carga -- ver el javadoc de la migración para el contraste con el
 * bug del proyecto de referencia (PK autoincremental + falta de UNIQUE).
 */
@Entity
@Table(name = "provincia")
@Getter
@Setter
@NoArgsConstructor
public class Provincia {

    @Id
    @Column(name = "codigo", length = 1)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = 50)
    private String nombre;
}
