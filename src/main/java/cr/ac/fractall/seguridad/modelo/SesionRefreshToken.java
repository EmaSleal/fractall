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

@Entity
@Table(name = "sesion_refresh_token")
@Getter
@Setter
@NoArgsConstructor
public class SesionRefreshToken extends EntidadBase {

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "dispositivo", length = 255)
    private String dispositivo;

    @Column(name = "ip_origen", length = 45)
    private String ipOrigen;

    @Column(name = "emitido_en", nullable = false)
    private LocalDateTime emitidoEn;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "revocado", nullable = false)
    private boolean revocado;

    @Column(name = "revocado_en")
    private LocalDateTime revocadoEn;
}
