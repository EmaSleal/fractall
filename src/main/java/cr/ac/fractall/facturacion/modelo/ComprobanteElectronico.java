package cr.ac.fractall.facturacion.modelo;

import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "comprobante_electronico",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"empresa_id", "ambiente_hacienda", "tipo_comprobante", "consecutivo"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ComprobanteElectronico extends TenantAwareEntity {

    @Column(name = "factura_id", nullable = false, unique = true)
    private UUID facturaId;

    @Column(name = "ambiente_hacienda", nullable = false, length = 10)
    private String ambienteHacienda;

    @Column(name = "tipo_comprobante", nullable = false, length = 2)
    private String tipoComprobante;

    @Column(name = "consecutivo", nullable = false, length = 20)
    private String consecutivo;

    @Column(name = "clave_numerica", nullable = false, unique = true, length = 50)
    private String claveNumerica;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "xml_comprobante")
    private String xmlComprobante;

    @Column(name = "xml_respuesta")
    private String xmlRespuesta;

    @Column(name = "codigo_respuesta", length = 10)
    private String codigoRespuesta;

    @Column(name = "mensaje_respuesta", length = 500)
    private String mensajeRespuesta;

    @Column(name = "intentos_envio", nullable = false)
    private int intentosEnvio;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDateTime fechaEmision;

    @Column(name = "fecha_respuesta")
    private LocalDateTime fechaRespuesta;
}
