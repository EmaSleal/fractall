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
@Table(name = "usuario_token")
@Getter
@Setter
@NoArgsConstructor
public class UsuarioToken extends EntidadBase {

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "tipo", nullable = false, length = 25)
    private String tipo;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "usado", nullable = false)
    private boolean usado;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;
}
