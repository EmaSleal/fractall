package cr.ac.fractall.facturacion.modelo;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Clase de llave compuesta ({@code @IdClass}) de {@link ContadorConsecutivo}.
 *
 * <p>Los tres campos deben coincidir en nombre y tipo con los campos {@code @Id} de la entidad
 * -- incluido {@code empresaId}, que en {@link ContadorConsecutivo} es simultáneamente
 * componente de esta llave y el discriminador {@code @TenantId} de Hibernate. Ver sección 4.9 y
 * 5 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
public class ContadorConsecutivoId implements Serializable {

    private UUID empresaId;
    private String ambiente;
    private String tipoComprobante;

    public ContadorConsecutivoId() {
    }

    public ContadorConsecutivoId(UUID empresaId, String ambiente, String tipoComprobante) {
        this.empresaId = empresaId;
        this.ambiente = ambiente;
        this.tipoComprobante = tipoComprobante;
    }

    public UUID getEmpresaId() {
        return empresaId;
    }

    public String getAmbiente() {
        return ambiente;
    }

    public String getTipoComprobante() {
        return tipoComprobante;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContadorConsecutivoId that)) {
            return false;
        }
        return Objects.equals(empresaId, that.empresaId)
                && Objects.equals(ambiente, that.ambiente)
                && Objects.equals(tipoComprobante, that.tipoComprobante);
    }

    @Override
    public int hashCode() {
        return Objects.hash(empresaId, ambiente, tipoComprobante);
    }
}
