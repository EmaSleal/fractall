package cr.ac.fractall.hacienda.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta (envío o consulta) de un comprobante a/desde la API de recepción de Hacienda.
 *
 * <p>Portado (Categoría A) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../dto/RespuestaHaciendaDTO.java}, con
 * {@code id}/{@code comprobanteId}/{@code createdAt} deliberadamente omitidos: esos tres campos
 * solo tenían sentido junto a la entidad JPA {@code RespuestaHacienda} del ERP original (para
 * persistir un historial de respuestas), y esa entidad -- junto con su repositorio y migración --
 * está fuera de alcance de esta porción de la Fase 8 (ver el javadoc de
 * {@link cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService}). Este DTO es, por
 * ahora, un valor transitorio devuelto al llamador.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RespuestaHaciendaDTO {

    private String claveNumerica;

    private LocalDateTime fechaRespuesta;

    private MensajeHacienda codigoMensaje;

    private String mensaje;

    private String xmlRespuesta;

    private String indicadorEstado;

    private Boolean exitoso;

    private String detalles;

    private Integer codigoHttp;

    private Long tiempoRespuestaMs;

    private Boolean debeReintentar;
}
