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
 * Registro append-only de un cobro parcial o total sobre una factura a plazo
 * ({@code condicion_venta IN ('02','03','04')}). El saldo no se persiste aqui: se deriva en
 * {@link FacturaEstadoCobro}.
 */
@Entity
@Table(name = "cobro_factura")
@Getter
@Setter
@NoArgsConstructor
public class CobroFactura extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false)
    private UUID facturaId;

    @Column(name = "monto_cobrado", nullable = false, precision = 14, scale = 5)
    private BigDecimal montoCobrado;

    @Column(name = "fecha_cobro", nullable = false)
    private LocalDateTime fechaCobro;

    @Column(name = "medio_pago", nullable = false, length = 2)
    private String medioPago;

    @Column(name = "referencia", length = 100)
    private String referencia;

    @Column(name = "registrado_por", nullable = false)
    private UUID registradoPor;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;
}
