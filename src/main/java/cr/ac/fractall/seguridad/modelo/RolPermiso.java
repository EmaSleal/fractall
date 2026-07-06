package cr.ac.fractall.seguridad.modelo;

import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * La unicidad de la fila vigente por par rol-permiso (sin {@code vigente_hasta})
 * se aplica con un índice único parcial a nivel de motor ({@code ux_rol_permiso_vigente}),
 * no replicado aquí.
 */
@Entity
@Table(name = "rol_permiso")
@Getter
@Setter
@NoArgsConstructor
public class RolPermiso extends EntidadBase {

    @Column(name = "rol_id", nullable = false)
    private UUID rolId;

    @Column(name = "permiso_id", nullable = false)
    private UUID permisoId;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDateTime vigenteDesde;

    @Column(name = "vigente_hasta")
    private LocalDateTime vigenteHasta;

    @Column(name = "asignado_por")
    private UUID asignadoPor;
}
