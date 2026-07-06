package cr.ac.fractall.seguridad.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Campos mínimos de {@code POST /auth/registro} (sección 3.1). Se limita estrictamente a
 * las columnas {@code NOT NULL} de {@code usuario} y {@code empresa} sin default de base de
 * datos utilizable desde Java ({@code nombre}, {@code email}, {@code password_hash},
 * {@code razon_social}) -- el resto del perfil fiscal de la empresa (identificación,
 * actividad, ubicación, certificado) se completa en un paso posterior, ya cubierto por la
 * máquina de estados de {@code empresa.status} (Fase 5, ver {@code fn_actualizar_status_empresa}).
 */
public record RegistroRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255)
        String nombre,

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password,

        @NotBlank(message = "La razón social es obligatoria")
        @Size(max = 255)
        String razonSocial) {
}
