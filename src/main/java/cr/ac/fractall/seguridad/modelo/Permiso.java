package cr.ac.fractall.seguridad.modelo;

import java.time.LocalDateTime;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "permiso")
@Getter
@Setter
@NoArgsConstructor
public class Permiso extends EntidadBase {

    @Column(name = "codigo", nullable = false, unique = true, length = 50)
    private String codigo;

    @Column(name = "modulo", nullable = false, length = 30)
    private String modulo;

    @Column(name = "descripcion", nullable = false, length = 255)
    private String descripcion;

    @Column(name = "critico", nullable = false)
    private boolean critico;

    @Column(name = "activo", nullable = false)
    private boolean activo;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;
}
