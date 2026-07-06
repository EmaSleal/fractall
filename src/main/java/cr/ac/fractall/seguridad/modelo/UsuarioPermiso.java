package cr.ac.fractall.seguridad.modelo;

import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "usuario_permiso", uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_empresa_id", "permiso_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UsuarioPermiso extends EntidadBase {

    @Column(name = "usuario_empresa_id", nullable = false)
    private UUID usuarioEmpresaId;

    @Column(name = "permiso_id", nullable = false)
    private UUID permisoId;

    @Column(name = "tipo", nullable = false, length = 10)
    private String tipo;

    @Column(name = "otorgado_por", nullable = false)
    private UUID otorgadoPor;

    @Column(name = "fecha_otorgado", nullable = false)
    private LocalDateTime fechaOtorgado;
}
