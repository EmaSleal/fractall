package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para respuesta de consulta a la API de Hacienda Costa Rica.
 * Endpoint: https://api.hacienda.go.cr/fe/ae?identificacion={numero}
 *
 * <p>Portado de {@code docs/proyecto-referencia/erp_spring_manager} (Categoría A) -- sin
 * consumidor propio en la Fase 6, reservado para {@code consultarContribuyente} en una fase
 * futura (ver el package-info de {@code cr.ac.fractall.hacienda}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HaciendaConsultaDTO {

    /**
     * Nombre completo o razón social
     */
    private String nombre;

    /**
     * Tipo de identificación (01=Física, 02=Jurídica, 03=DIMEX, 04=NITE)
     */
    private String tipoIdentificacion;

    /**
     * Régimen tributario
     */
    private HaciendaRegimenDTO regimen;

    /**
     * Situación tributaria actual
     */
    private HaciendaSituacionDTO situacion;

    /**
     * Lista de actividades económicas
     */
    private List<HaciendaActividadDTO> actividades;

    /**
     * Indica si la consulta fue exitosa
     */
    private Boolean exitosa;

    /**
     * Mensaje de error si la consulta falló
     */
    private String mensajeError;

    /**
     * Obtiene la actividad principal (tipo P)
     *
     * @return Actividad principal o null si no existe
     */
    public HaciendaActividadDTO getActividadPrincipal() {
        if (actividades == null || actividades.isEmpty()) {
            return null;
        }

        return actividades.stream()
            .filter(act -> "P".equals(act.getTipo()) && "A".equals(act.getEstado()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Verifica si el contribuyente está al día
     *
     * @return true si no está moroso ni omiso
     */
    public boolean isEstaAlDia() {
        if (situacion == null) {
            return false;
        }
        return "NO".equalsIgnoreCase(situacion.getMoroso()) &&
               "NO".equalsIgnoreCase(situacion.getOmiso());
    }

    /**
     * Verifica si está inscrito activamente
     *
     * @return true si está inscrito
     */
    public boolean isEstaInscrito() {
        if (situacion == null) {
            return false;
        }
        return "Inscrito".equalsIgnoreCase(situacion.getEstado());
    }
}
