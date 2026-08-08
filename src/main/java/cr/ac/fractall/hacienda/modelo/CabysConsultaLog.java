package cr.ac.fractall.hacienda.modelo;

import java.time.LocalDateTime;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registro crudo de cada item que la API de Hacienda devolvió en una consulta CABYS (por código
 * o por texto), sin deduplicar ni resolver todavía contra el cache ({@link Cabys}) -- eso lo hace
 * {@code CabysReconciliacionJob} de forma asíncrona (diaria), no la llamada HTTP en sí, para no
 * alargar la respuesta al usuario que está creando/editando un producto.
 */
@Entity
@Table(name = "cabys_consulta_log")
@Getter
@Setter
@NoArgsConstructor
public class CabysConsultaLog extends EntidadBase {

    @Column(name = "codigo", nullable = false, length = 13)
    private String codigo;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    @Column(name = "categorias")
    private String categorias;

    @Column(name = "impuesto")
    private Short impuesto;

    @Column(name = "uri", length = 255)
    private String uri;

    @Column(name = "estado", length = 20)
    private String estado;

    @Column(name = "consultado_en", nullable = false)
    private LocalDateTime consultadoEn;

    @Column(name = "procesado", nullable = false)
    private boolean procesado;

    @Column(name = "procesado_en")
    private LocalDateTime procesadoEn;
}
