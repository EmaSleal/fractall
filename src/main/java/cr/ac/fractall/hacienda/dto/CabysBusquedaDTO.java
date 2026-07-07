package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para respuesta de búsqueda de códigos CABYS desde la API de Hacienda.
 * Endpoint: https://api.hacienda.go.cr/fe/cabys?q={busqueda}&amp;top={cantidad}
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) para la Fase
 * 6 -- ver sección 4.10 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CabysBusquedaDTO {

    /**
     * Total de resultados encontrados
     */
    private Integer total;

    /**
     * Cantidad de resultados devueltos (limitado por 'top')
     */
    private Integer cantidad;

    /**
     * Lista de códigos CABYS encontrados
     */
    private List<CabysDTO> cabys;

    /**
     * Indica si la búsqueda fue exitosa (para control interno)
     */
    private Boolean exitosa;

    /**
     * Mensaje de error si la búsqueda falló
     */
    private String mensajeError;

    /**
     * Verifica si hay resultados
     *
     * @return true si encontró al menos un resultado
     */
    public boolean tieneResultados() {
        return cabys != null && !cabys.isEmpty();
    }

    /**
     * Obtiene el primer resultado (más relevante)
     *
     * @return Primer código CABYS o null
     */
    public CabysDTO getPrimerResultado() {
        if (!tieneResultados()) {
            return null;
        }
        return cabys.get(0);
    }
}
