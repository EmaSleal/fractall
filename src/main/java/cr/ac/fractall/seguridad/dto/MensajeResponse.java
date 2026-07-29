package cr.ac.fractall.seguridad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Respuesta genérica de un solo mensaje -- usada donde el detalle no debe filtrarse. */
@Schema(description = "Generic message response used for errors and confirmations")
public record MensajeResponse(
        @Schema(description = "Human-readable message. For 400 validation errors, format is 'fieldPath: message; fieldPath2: message2'") String mensaje) {
}
