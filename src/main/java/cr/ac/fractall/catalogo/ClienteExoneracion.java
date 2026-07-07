package cr.ac.fractall.catalogo;

import java.math.BigDecimal;
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

/**
 * Autorización de exoneración de un cliente específico (sección 4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- data maestra ligada al cliente,
 * potencialmente reutilizable en múltiples facturas durante su periodo de validez. Su
 * *consumo* dentro de la creación de una {@code linea_factura} es trabajo de la Fase 7 (ver
 * {@code V8__cliente_exoneracion.sql}).
 *
 * <p>El catálogo de 12 códigos de {@code tipoDocumento} y la regla "'99' exige
 * {@code nombreInstitucionOtros}" ya los aplica el {@code CHECK} de motor -- se revalidan
 * también en {@code ClienteExoneracionService} para rechazar con una excepción de dominio
 * limpia (400) antes de {@code saveAndFlush}, no como una
 * {@code DataIntegrityViolationException} sin capturar.
 */
@Entity
@Table(name = "cliente_exoneracion", uniqueConstraints = @UniqueConstraint(columnNames = {"cliente_id", "numero_documento"}))
@Getter
@Setter
@NoArgsConstructor
public class ClienteExoneracion extends TenantAwareEntity {

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(name = "tipo_documento", nullable = false, length = 2)
    private String tipoDocumento;

    @Column(name = "numero_documento", nullable = false, length = 40)
    private String numeroDocumento;

    @Column(name = "nombre_institucion", nullable = false, length = 160)
    private String nombreInstitucion;

    @Column(name = "numero_articulo", nullable = false, length = 10)
    private String numeroArticulo;

    @Column(name = "inciso", length = 10)
    private String inciso;

    @Column(name = "nombre_institucion_otros", length = 160)
    private String nombreInstitucionOtros;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDateTime fechaEmision;

    @Column(name = "fecha_vencimiento")
    private LocalDateTime fechaVencimiento;

    @Column(name = "porcentaje_exoneracion", nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentajeExoneracion;

    @Column(name = "activo", nullable = false)
    private boolean activo;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
