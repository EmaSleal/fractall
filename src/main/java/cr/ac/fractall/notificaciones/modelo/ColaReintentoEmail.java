package cr.ac.fractall.notificaciones.modelo;

import java.time.LocalDateTime;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cola de reintento acotada al correo de verificación de email (sección 3.1 del documento
 * de arquitectura). No tiene {@code empresa_id}: no es tenant-scoped, vive fuera del
 * contexto de ninguna empresa en particular (se dispara antes de que exista una sesión con
 * tenant resuelto).
 */
@Entity
@Table(name = "cola_reintento_email")
@Getter
@Setter
@NoArgsConstructor
public class ColaReintentoEmail extends EntidadBase {

    @Column(name = "destinatario", nullable = false, length = 255)
    private String destinatario;

    @Column(name = "asunto", nullable = false, length = 255)
    private String asunto;

    @Column(name = "cuerpo_html", nullable = false)
    private String cuerpoHtml;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "proximo_intento", nullable = false)
    private LocalDateTime proximoIntento;

    @Column(name = "estado", nullable = false, length = 10)
    private String estado;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;
}
