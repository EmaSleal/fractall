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
@Table(name = "factura_otros_cargos")
@Getter
@Setter
@NoArgsConstructor
public class FacturaOtrosCargos extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false)
    private UUID facturaId;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "tipo_documento_oc", nullable = false, length = 2)
    private String tipoDocumentoOc;

    @Column(name = "tipo_documento_otros", length = 100)
    private String tipoDocumentoOtros;

    @Column(name = "identidad_tipo", length = 2)
    private String identidadTipo;

    @Column(name = "identidad_numero", length = 20)
    private String identidadNumero;

    @Column(name = "nombre_tercero", length = 100)
    private String nombreTercero;

    @Column(name = "detalle", nullable = false, length = 200)
    private String detalle;

    @Column(name = "porcentaje_oc", precision = 4, scale = 2)
    private BigDecimal porcentajeOc;

    @Column(name = "monto_cargo", nullable = false, precision = 18, scale = 5)
    private BigDecimal montoCargo;
}
