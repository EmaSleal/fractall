package cr.ac.fractall.facturacion.modelo;

import java.math.BigDecimal;
import java.util.UUID;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Los 3 campos de exoneración ({@code exoneracionId}/{@code porcentajeExoneracionAplicado}/
 * {@code montoExoneracionAplicado}) son todo-o-nada -- aplicado con un CHECK a nivel de motor
 * ({@code chk_exoneracion_todo_o_nada}, V10) y verificado explícitamente en Java por
 * {@code FacturaService} antes de persistir (ver su javadoc para el porqué de la duplicación).
 */
@Entity
@Table(name = "linea_factura", uniqueConstraints = @UniqueConstraint(columnNames = {"factura_id", "numero_linea"}))
@Getter
@Setter
@NoArgsConstructor
public class LineaFactura extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false)
    private UUID facturaId;

    @Column(name = "producto_id", nullable = false)
    private UUID productoId;

    @Column(name = "numero_linea", nullable = false)
    private int numeroLinea;

    @Column(name = "cantidad", nullable = false, precision = 10, scale = 3)
    private BigDecimal cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 14, scale = 5)
    private BigDecimal precioUnitario;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 5)
    private BigDecimal subtotal;

    @Column(name = "codigo_cabys_aplicado", nullable = false, length = 13)
    private String codigoCabysAplicado;

    @Column(name = "gravado_aplicado", nullable = false)
    private boolean gravadoAplicado;

    @Column(name = "porcentaje_impuesto_aplicado", nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentajeImpuestoAplicado;

    @Column(name = "exoneracion_id")
    private UUID exoneracionId;

    @Column(name = "porcentaje_exoneracion_aplicado", precision = 5, scale = 2)
    private BigDecimal porcentajeExoneracionAplicado;

    @Column(name = "monto_exoneracion_aplicado", precision = 14, scale = 5)
    private BigDecimal montoExoneracionAplicado;
}
