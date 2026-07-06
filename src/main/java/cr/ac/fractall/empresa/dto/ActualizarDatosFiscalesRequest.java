package cr.ac.fractall.empresa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PATCH /empresa} -- actualización PARCIAL de la configuración fiscal
 * (sección 4.1): cualquier campo en {@code null} se deja intacto en {@code empresa}, nunca se
 * sobrescribe con {@code null}. La transición de {@code empresa.status} nunca se decide aquí
 * -- la recalcula {@code fn_actualizar_status_empresa} al guardar (ver
 * {@code EmpresaService#actualizarDatosFiscales}).
 */
public record ActualizarDatosFiscalesRequest(

        @Size(max = 255)
        String razonSocial,

        @Size(max = 255)
        String nombreComercial,

        @Size(max = 20)
        String numeroIdentificacion,

        @Size(max = 2)
        String tipoIdentificacion,

        @Size(max = 6)
        String codigoActividad,

        @Size(max = 1)
        String codigoProvincia,

        @Size(max = 2)
        String canton,

        @Size(max = 2)
        String distrito,

        @Size(max = 100)
        String barrio,

        @Size(max = 300)
        String otrasSenas,

        @Size(max = 20)
        String telefono,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email) {
}
