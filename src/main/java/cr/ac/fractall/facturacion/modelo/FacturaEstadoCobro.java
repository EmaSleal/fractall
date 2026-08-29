package cr.ac.fractall.facturacion.modelo;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Primera entidad mapeada sobre una VISTA en este codebase. NO extiende {@code TenantAwareEntity}:
 * esa superclase hereda de {@code EntidadBase}, cuyo {@code @Id} mapea una columna {@code id} con
 * {@code @Generated(event = INSERT)} ({@code EntidadBase:28-31}) -- la vista no tiene columna
 * {@code id} y no genera nada. {@code @TenantId} se declara aqui directamente; es una anotacion
 * de Hibernate, no algo que {@code TenantAwareEntity} aporte por herencia.
 *
 * <p>{@code @Immutable} evita que Hibernate intente flush/dirty-checking sobre filas de solo
 * lectura. El unico precedente de vista en el proyecto ({@code permisos_efectivos}, V3:71) se lee
 * por query nativa ({@code UsuarioEmpresaRepository:36-38}); se elige entidad aqui para heredar
 * el filtro de tenant y para que una fase futura pueda unirla en JPQL.
 */
@Entity
@Immutable
@Table(name = "factura_estado_cobro")
@Getter
@NoArgsConstructor
public class FacturaEstadoCobro {

    @Id
    @Column(name = "factura_id", insertable = false, updatable = false)
    private UUID facturaId;

    @TenantId
    @Column(name = "empresa_id", insertable = false, updatable = false)
    private UUID empresaId;

    @Column(name = "total", insertable = false, updatable = false, precision = 14, scale = 5)
    private BigDecimal total;

    @Column(name = "total_nota_credito", insertable = false, updatable = false, precision = 14, scale = 5)
    private BigDecimal totalNotaCredito;

    @Column(name = "total_neto", insertable = false, updatable = false, precision = 14, scale = 5)
    private BigDecimal totalNeto;

    @Column(name = "total_cobrado", insertable = false, updatable = false, precision = 14, scale = 5)
    private BigDecimal totalCobrado;

    @Column(name = "saldo_pendiente", insertable = false, updatable = false, precision = 14, scale = 5)
    private BigDecimal saldoPendiente;

    @Column(name = "estado_cobro", insertable = false, updatable = false, length = 20)
    private String estadoCobro;
}
