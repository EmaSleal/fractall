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
 * Invitación de un {@code usuario} a unirse a una {@code empresa} que no creó.
 *
 * <p>Extiende {@code EntidadBase} con un {@code empresaId} plano, NO {@code TenantAwareEntity}
 * (sin {@code @TenantId}) -- ver la discusión 1 de design.md: ambos caminos de consumo resuelven
 * la fila ANTES de conocer la empresa que invita. {@code POST /auth/registro/invitacion} corre
 * bajo {@code TenantContextDescartable} (un UUID aleatorio de un solo uso), y
 * {@code POST /usuarios/invitacion/{token}/aceptar} corre bajo el tenant ACTUAL del invitado, que
 * por definición no es el que invita. Un filtro {@code @TenantId} descartaría ambas filas antes
 * de poder leerlas. Mismo criterio que {@link UsuarioEmpresa}.
 */
@Entity
@Table(name = "invitacion_usuario")
@Getter
@Setter
@NoArgsConstructor
public class InvitacionUsuario extends EntidadBase {

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "rol_id", nullable = false)
    private UUID rolId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "invitado_por", nullable = false)
    private UUID invitadoPor;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;
}
