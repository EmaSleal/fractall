package cr.ac.fractall.catalogo.modelo;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Llave natural compuesta de {@link Canton}: {@code (provincia_codigo, codigo)}.
 *
 * <p>{@code equals}/{@code hashCode} manuales (no delegados a Lombok) por el mismo motivo que
 * {@code ContadorConsecutivoId}: son la base de cómo Hibernate compara identidad de entidad para
 * una llave embebida, así que quedan explícitos en vez de depender de generación por anotación.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class CantonId implements Serializable {

    @Column(name = "provincia_codigo", length = 1)
    private String provinciaCodigo;

    @Column(name = "codigo", length = 2)
    private String codigo;

    public CantonId(String provinciaCodigo, String codigo) {
        this.provinciaCodigo = provinciaCodigo;
        this.codigo = codigo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CantonId that)) {
            return false;
        }
        return Objects.equals(provinciaCodigo, that.provinciaCodigo) && Objects.equals(codigo, that.codigo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provinciaCodigo, codigo);
    }
}
