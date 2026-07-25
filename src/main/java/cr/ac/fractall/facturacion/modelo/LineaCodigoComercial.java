package cr.ac.fractall.facturacion.modelo;

import java.util.UUID;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "linea_codigo_comercial")
@Getter
@Setter
@NoArgsConstructor
public class LineaCodigoComercial extends TenantAwareEntity {

    @Column(name = "linea_id", nullable = false)
    private UUID lineaId;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "tipo", nullable = false, length = 2)
    private String tipo;

    @Column(name = "codigo", nullable = false, length = 255)
    private String codigo;
}
