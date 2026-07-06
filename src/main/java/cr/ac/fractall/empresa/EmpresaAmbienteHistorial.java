package cr.ac.fractall.empresa;

import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "empresa_ambiente_historial")
@Getter
@Setter
@NoArgsConstructor
public class EmpresaAmbienteHistorial extends EntidadBase {

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "ambiente_anterior", nullable = false, length = 10)
    private String ambienteAnterior;

    @Column(name = "ambiente_nuevo", nullable = false, length = 10)
    private String ambienteNuevo;

    @Column(name = "activado_por", nullable = false)
    private UUID activadoPor;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;
}
