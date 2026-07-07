package cr.ac.fractall.empresa.modelo;

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
@Table(name = "credencial_hacienda", uniqueConstraints = @UniqueConstraint(columnNames = {"empresa_id", "ambiente"}))
@Getter
@Setter
@NoArgsConstructor
public class CredencialHacienda extends EntidadBase {

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "ambiente", nullable = false, length = 10)
    private String ambiente;

    @Column(name = "usuario_hacienda", nullable = false, length = 255)
    private String usuarioHacienda;

    @Column(name = "credencial_referencia", nullable = false, length = 255)
    private String credencialReferencia;

    @Column(name = "configurada_en", nullable = false)
    private LocalDateTime configuradaEn;

    @Column(name = "configurada_por", nullable = false)
    private UUID configuradaPor;
}
