package cr.ac.fractall.facturacion.modelo;

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
@Table(name = "factura_informacion_referencia")
@Getter
@Setter
@NoArgsConstructor
public class FacturaInformacionReferencia extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false)
    private UUID facturaId;

    @Column(name = "orden", nullable = false)
    private short orden;

    @Column(name = "tipo_doc_ir", nullable = false, length = 2)
    private String tipoDocIr;

    @Column(name = "tipo_doc_ref_otro", length = 100)
    private String tipoDocRefOtro;

    @Column(name = "numero", length = 50)
    private String numero;

    @Column(name = "fecha_emision_ir", nullable = false)
    private LocalDateTime fechaEmisionIr;

    @Column(name = "codigo", length = 2)
    private String codigo;

    @Column(name = "codigo_referencia_otro", length = 100)
    private String codigoReferenciaOtro;

    @Column(name = "razon", length = 180)
    private String razon;
}
