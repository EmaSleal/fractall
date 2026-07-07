package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para situación tributaria desde la API de Hacienda Costa Rica.
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) -- referenciado
 * por {@link HaciendaConsultaDTO}, sin consumidor propio en la Fase 6 (reservado para
 * {@code consultarContribuyente}, ver el package-info de {@code cr.ac.fractall.hacienda}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HaciendaSituacionDTO {

    /**
     * Indica si está moroso (SI/NO)
     */
    private String moroso;

    /**
     * Indica si está omiso (SI/NO)
     */
    private String omiso;

    /**
     * Estado tributario (Inscrito, Suspendido, Cancelado)
     */
    private String estado;

    /**
     * Administración tributaria que lo gestiona
     */
    private String administracionTributaria;
}
