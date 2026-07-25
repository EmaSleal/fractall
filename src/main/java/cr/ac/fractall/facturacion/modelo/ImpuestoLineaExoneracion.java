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

@Entity
@Table(name = "impuesto_linea_exoneracion")
@Getter
@Setter
@NoArgsConstructor
public class ImpuestoLineaExoneracion extends TenantAwareEntity {

    @Column(name = "linea_id", nullable = false, unique = true)
    private UUID lineaId;

    @Column(name = "tipo_documento_ex1", nullable = false, length = 2)
    private String tipoDocumentoEx1;

    @Column(name = "tipo_documento_otro", length = 100)
    private String tipoDocumentoOtro;

    @Column(name = "numero_documento", nullable = false, length = 40)
    private String numeroDocumento;

    @Column(name = "articulo")
    private Integer articulo;

    @Column(name = "inciso")
    private Integer inciso;

    @Column(name = "nombre_institucion", nullable = false, length = 2)
    private String nombreInstitucion;

    @Column(name = "nombre_institucion_otros", length = 100)
    private String nombreInstitucionOtros;

    @Column(name = "fecha_emision_ex", nullable = false)
    private LocalDateTime fechaEmisionEx;

    @Column(name = "tarifa_exonerada", nullable = false, precision = 4, scale = 2)
    private BigDecimal tarifaExonerada;

    @Column(name = "monto_exoneracion", nullable = false, precision = 18, scale = 5)
    private BigDecimal montoExoneracion;
}
