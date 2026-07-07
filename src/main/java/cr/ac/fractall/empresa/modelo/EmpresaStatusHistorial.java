package cr.ac.fractall.empresa.modelo;

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
@Table(name = "empresa_status_historial")
@Getter
@Setter
@NoArgsConstructor
public class EmpresaStatusHistorial extends EntidadBase {

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "status_anterior", length = 35)
    private String statusAnterior;

    @Column(name = "status_nuevo", nullable = false, length = 35)
    private String statusNuevo;

    @Column(name = "tipo_cambio", nullable = false, length = 15)
    private String tipoCambio;

    @Column(name = "motivo", length = 255)
    private String motivo;

    @Column(name = "ejecutado_por")
    private UUID ejecutadoPor;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;
}
