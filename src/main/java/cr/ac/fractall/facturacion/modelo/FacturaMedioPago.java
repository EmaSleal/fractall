package cr.ac.fractall.facturacion.modelo;

import java.math.BigDecimal;
import java.util.UUID;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "factura_medio_pago")
@Getter
@Setter
@NoArgsConstructor
public class FacturaMedioPago extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false)
    private UUID facturaId;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "tipo_medio_pago", nullable = false, length = 2)
    private String tipoMedioPago;

    @Column(name = "medio_pago_otros", length = 100)
    private String medioPagoOtros;

    @Column(name = "total_medio_pago", nullable = false, precision = 18, scale = 5)
    private BigDecimal totalMedioPago;
}
