package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para actividad económica desde la API de Hacienda Costa Rica.
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) -- referenciado
 * por {@link HaciendaConsultaDTO}, sin consumidor propio en la Fase 6.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HaciendaActividadDTO {

    /**
     * Estado de la actividad (A=Activa, I=Inactiva)
     */
    private String estado;

    /**
     * Tipo de actividad (P=Principal, S=Secundaria)
     */
    private String tipo;

    /**
     * Código de actividad económica (CAECR)
     */
    private String codigo;

    /**
     * Descripción de la actividad
     */
    private String descripcion;

    /**
     * Clasificación CIIU versión 3
     */
    private List<HaciendaCIIUDTO> ciiu3;
}
