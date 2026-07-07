package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para régimen tributario desde la API de Hacienda Costa Rica.
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
public class HaciendaRegimenDTO {

    /**
     * Código del régimen (1=General, 2=Simplificado, 3=No contribuyente)
     */
    private Integer codigo;

    /**
     * Descripción del régimen
     */
    private String descripcion;
}
