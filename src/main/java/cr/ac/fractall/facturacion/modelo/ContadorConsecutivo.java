package cr.ac.fractall.facturacion.modelo;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Contador de consecutivos de comprobantes electrónicos, separado por ambiente (sección 4.9 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Sin llave sustituta: la llave primaria real de {@code contador_consecutivo} es la
 * combinación {@code (empresa_id, ambiente, tipo_comprobante)}, mapeada aquí vía
 * {@link ContadorConsecutivoId}. Por eso esta entidad NO extiende
 * {@link cr.ac.fractall.shared.EntidadBase} ni {@link cr.ac.fractall.tenant.TenantAwareEntity}
 * -- ambas superclases asumen un {@code @Id} sustituto UUID separado de la columna de tenant.
 *
 * <p>{@code empresaId} es a la vez componente de la llave compuesta ({@code @Id}) Y el
 * discriminador de tenant ({@code @TenantId}) que Hibernate filtra automáticamente en toda
 * consulta JPQL/Criteria de esta sesión -- combinación no usada en ningún otro punto de este
 * código base antes de esta entidad. Queda probada empíricamente por
 * {@code ContadorConsecutivoAislamientoTest}: dos empresas distintas con el mismo
 * {@code (ambiente, tipoComprobante)} nunca ven ni afectan la fila de la otra, aun cuando
 * {@code empresaId} se pasa también de forma explícita como parte de la llave en
 * {@code ContadorConsecutivoRepository#findById}.
 */
@Entity
@Table(name = "contador_consecutivo")
@IdClass(ContadorConsecutivoId.class)
@Getter
@Setter
@NoArgsConstructor
public class ContadorConsecutivo {

    @Id
    @TenantId
    @Column(name = "empresa_id", nullable = false, updatable = false)
    private UUID empresaId;

    @Id
    @Column(name = "ambiente", nullable = false, length = 10, updatable = false)
    private String ambiente;

    @Id
    @Column(name = "tipo_comprobante", nullable = false, length = 2, updatable = false)
    private String tipoComprobante;

    @Column(name = "valor_actual", nullable = false)
    private long valorActual;

    public ContadorConsecutivo(UUID empresaId, String ambiente, String tipoComprobante, long valorActual) {
        this.empresaId = empresaId;
        this.ambiente = ambiente;
        this.tipoComprobante = tipoComprobante;
        this.valorActual = valorActual;
    }
}
