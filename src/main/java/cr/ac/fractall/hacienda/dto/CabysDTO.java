package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para un código CABYS individual desde la API de Hacienda Costa Rica.
 * Cada código CABYS tiene 13 dígitos y clasifica productos/servicios.
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) para la Fase
 * 6 -- ver sección 4.10 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CabysDTO {

    /**
     * Código CABYS de 13 dígitos
     * Ejemplo: "2132100000100"
     */
    private String codigo;

    /**
     * Descripción del producto/servicio
     * Ejemplo: "Jugo de tomate concentrado"
     */
    private String descripcion;

    /**
     * Categorías jerárquicas del código CABYS
     * Desde la más general hasta la más específica
     */
    private List<String> categorias;

    /**
     * Porcentaje de impuesto aplicable (IVA)
     * Ejemplo: 13 (13%)
     */
    private Integer impuesto;

    /**
     * URI para consultar más detalles del código
     */
    private String uri;

    /**
     * Estado del código (activo/inactivo)
     */
    private String estado;

    /**
     * Obtiene la categoría más específica (última en la lista)
     *
     * @return Categoría más específica o descripción si no hay categorías
     */
    public String getCategoriaEspecifica() {
        if (categorias == null || categorias.isEmpty()) {
            return descripcion;
        }
        return categorias.get(categorias.size() - 1);
    }

    /**
     * Formatea el código CABYS con guiones para mejor legibilidad
     * Formato: XXXX-XXX-XXXX-XX
     *
     * @return Código formateado
     */
    public String getCodigoFormateado() {
        if (codigo == null || codigo.length() != 13) {
            return codigo;
        }
        return String.format("%s-%s-%s-%s",
            codigo.substring(0, 4),
            codigo.substring(4, 7),
            codigo.substring(7, 11),
            codigo.substring(11, 13)
        );
    }
}
