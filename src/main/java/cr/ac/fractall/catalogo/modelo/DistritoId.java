package cr.ac.fractall.catalogo.modelo;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Llave natural compuesta de {@link Distrito}: {@code (provincia_codigo, canton_codigo, codigo)}.
 *
 * <p>{@code equals}/{@code hashCode} manuales -- ver el javadoc de {@link CantonId}, mismo
 * motivo.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class DistritoId implements Serializable {

    @Column(name = "provincia_codigo", length = 1)
    private String provinciaCodigo;

    @Column(name = "canton_codigo", length = 2)
    private String cantonCodigo;

    @Column(name = "codigo", length = 2)
    private String codigo;

    public DistritoId(String provinciaCodigo, String cantonCodigo, String codigo) {
        this.provinciaCodigo = provinciaCodigo;
        this.cantonCodigo = cantonCodigo;
        this.codigo = codigo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DistritoId that)) {
            return false;
        }
        return Objects.equals(provinciaCodigo, that.provinciaCodigo)
                && Objects.equals(cantonCodigo, that.cantonCodigo)
                && Objects.equals(codigo, that.codigo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provinciaCodigo, cantonCodigo, codigo);
    }
}
