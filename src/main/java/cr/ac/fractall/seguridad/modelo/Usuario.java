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
@Table(name = "usuario")
@Getter
@Setter
@NoArgsConstructor
public class Usuario extends EntidadBase {

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "email_verificado", nullable = false)
    private boolean emailVerificado;

    // length=30: ver V6__ampliar_estado_usuario.sql -- 'PENDIENTE_VERIFICACION' (22
    // caracteres) no entraba en el VARCHAR(20) original de V1__esquema_base.sql.
    @Column(name = "estado", nullable = false, length = 30)
    private String estado;

    @Column(name = "mfa_habilitado", nullable = false)
    private boolean mfaHabilitado;

    @Column(name = "mfa_secret_cifrado")
    private byte[] mfaSecretCifrado;

    @Column(name = "intentos_fallidos", nullable = false)
    private int intentosFallidos;

    @Column(name = "bloqueada_hasta")
    private LocalDateTime bloqueadaHasta;

    @Column(name = "ultimo_login")
    private LocalDateTime ultimoLogin;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
