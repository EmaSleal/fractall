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
@Table(name = "usuario_empresa", uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_id", "empresa_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UsuarioEmpresa extends EntidadBase {

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "rol_id", nullable = false)
    private UUID rolId;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "invitado_por")
    private UUID invitadoPor;

    @Column(name = "fecha_ingreso", nullable = false)
    private LocalDateTime fechaIngreso;
}
