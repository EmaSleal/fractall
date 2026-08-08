package cr.ac.fractall.hacienda.modelo;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cache local (read-through) de códigos CABYS consultados a la API de Hacienda Costa Rica
 * (V16__catalogo_cabys.sql) -- ver el javadoc de {@code HaciendaConsultaServiceImpl} para cuándo
 * se sirve desde aquí y cuándo se llama a Hacienda en vivo.
 *
 * <p>Sin {@code id} sustituto: la clave natural ({@code codigo}, 13 dígitos) ES la PK, mismo
 * patrón que {@code cr.ac.fractall.catalogo.modelo.Provincia} -- ver su javadoc.
 *
 * <p>{@code categorias} se persiste como {@code String} simple unido con un separador de control
 * no imprimible ({@code SEPARADOR_CATEGORIAS} en {@code HaciendaConsultaServiceImpl}, U+001F) en
 * vez de JSONB o array: no hay precedente de esos tipos en este proyecto y no se justifica
 * introducirlos solo para esto.
 */
@Entity
@Table(name = "cabys")
@Getter
@Setter
@NoArgsConstructor
public class Cabys {

    @Id
    @Column(name = "codigo", length = 13)
    private String codigo;

    @Column(name = "descripcion", nullable = false, length = 255)
    private String descripcion;

    @Column(name = "categorias")
    private String categorias;

    @Column(name = "impuesto")
    private Short impuesto;

    @Column(name = "uri", length = 255)
    private String uri;

    @Column(name = "estado", length = 20)
    private String estado;

    @Column(name = "actualizado_en", nullable = false)
    private LocalDateTime actualizadoEn;
}
