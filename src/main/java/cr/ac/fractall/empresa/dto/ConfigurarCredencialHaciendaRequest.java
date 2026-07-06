package cr.ac.fractall.empresa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /empresa/credenciales-hacienda} -- ambiente {@code SANDBOX}
 * únicamente (sección 4.2; {@code PRODUCCION} queda fuera de alcance de la Fase 5, ver
 * {@code plan-fases-release-1.md}).
 */
public record ConfigurarCredencialHaciendaRequest(

        @NotBlank(message = "El usuario de Hacienda es obligatorio")
        @Size(max = 255)
        String usuarioHacienda,

        @NotBlank(message = "La contraseña de Hacienda es obligatoria")
        String password) {
}
