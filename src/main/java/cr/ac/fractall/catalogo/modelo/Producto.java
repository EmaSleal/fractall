package cr.ac.fractall.catalogo.modelo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "producto", uniqueConstraints = @UniqueConstraint(columnNames = {"empresa_id", "codigo"}))
@Getter
@Setter
@NoArgsConstructor
public class Producto extends TenantAwareEntity {

    @Column(name = "codigo", nullable = false, length = 50)
    private String codigo;

    @Column(name = "descripcion", nullable = false, length = 255)
    private String descripcion;

    @Column(name = "codigo_cabys", nullable = false, length = 13)
    private String codigoCabys;

    @Column(name = "descripcion_cabys", length = 255)
    private String descripcionCabys;

    @Column(name = "cabys_validado_en", nullable = false)
    private LocalDateTime cabysValidadoEn;

    @Column(name = "codigo_unidad_fe", nullable = false, length = 20)
    private String codigoUnidadFe;

    @Column(name = "precio_venta", nullable = false, precision = 14, scale = 5)
    private BigDecimal precioVenta;

    @Column(name = "gravado", nullable = false)
    private boolean gravado;

    @Column(name = "porcentaje_impuesto", nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentajeImpuesto;

    @Column(name = "activo", nullable = false)
    private boolean activo;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
