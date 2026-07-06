package cr.ac.fractall.catalogo;

import java.time.LocalDateTime;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * La coherencia "todo o nada" de la dirección (provincia/cantón/distrito/señas)
 * se aplica con un CHECK a nivel de motor, no replicado aquí.
 */
@Entity
@Table(name = "cliente", uniqueConstraints = @UniqueConstraint(columnNames = {"empresa_id", "numero_identificacion"}))
@Getter
@Setter
@NoArgsConstructor
public class Cliente extends TenantAwareEntity {

    @Column(name = "nombre", nullable = false, length = 255)
    private String nombre;

    @Column(name = "tipo_identificacion", nullable = false, length = 2)
    private String tipoIdentificacion;

    @Column(name = "numero_identificacion", nullable = false, length = 20)
    private String numeroIdentificacion;

    @Column(name = "codigo_actividad", length = 6)
    private String codigoActividad;

    @Column(name = "codigo_provincia", length = 1)
    private String codigoProvincia;

    @Column(name = "canton", length = 2)
    private String canton;

    @Column(name = "distrito", length = 2)
    private String distrito;

    @Column(name = "otras_senas", length = 300)
    private String otrasSenas;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "requiere_factura_electronica", nullable = false)
    private boolean requiereFacturaElectronica;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
