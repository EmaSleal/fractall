package cr.ac.fractall.facturacion.modelo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * La coherencia entre {@code condicion_venta = '02'} (crédito) y {@code plazo_credito}
 * no nulo se aplica con un CHECK a nivel de motor, no replicado aquí.
 */
@Entity
@Table(name = "factura")
@Getter
@Setter
@NoArgsConstructor
public class Factura extends TenantAwareEntity {

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(name = "condicion_venta", nullable = false, length = 2)
    private String condicionVenta;

    @Column(name = "plazo_credito")
    private Integer plazoCredito;

    @Column(name = "medio_pago", nullable = false, length = 2)
    private String medioPago;

    @Column(name = "moneda", nullable = false, length = 3)
    private String moneda;

    @Column(name = "tipo_cambio", nullable = false, precision = 10, scale = 5)
    private BigDecimal tipoCambio;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 5)
    private BigDecimal subtotal;

    @Column(name = "total_impuesto", nullable = false, precision = 14, scale = 5)
    private BigDecimal totalImpuesto;

    @Column(name = "total", nullable = false, precision = 14, scale = 5)
    private BigDecimal total;

    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
