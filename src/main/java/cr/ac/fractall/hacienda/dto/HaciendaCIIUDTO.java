package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para clasificación CIIU desde la API de Hacienda Costa Rica.
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) -- referenciado
 * por {@link HaciendaActividadDTO}, sin consumidor propio en la Fase 6.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HaciendaCIIUDTO {

    /**
     * Código CIIU
     */
    private String codigo;

    /**
     * Descripción de la clasificación
     */
    private String descripcion;
}
