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
@Table(name = "linea_descuento")
@Getter
@Setter
@NoArgsConstructor
public class LineaDescuento extends TenantAwareEntity {

    @Column(name = "linea_id", nullable = false)
    private UUID lineaId;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "monto_descuento", nullable = false, precision = 18, scale = 5)
    private BigDecimal montoDescuento;

    @Column(name = "codigo_descuento", length = 2)
    private String codigoDescuento;

    @Column(name = "codigo_descuento_otro", length = 100)
    private String codigoDescuentoOtro;

    @Column(name = "naturaleza_descuento", length = 80)
    private String naturalezaDescuento;
}
