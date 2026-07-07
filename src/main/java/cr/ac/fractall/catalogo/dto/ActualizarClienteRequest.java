package cr.ac.fractall.catalogo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PATCH /catalogo/clientes/{id}} -- actualización PARCIAL: un campo
 * {@code null} deja el valor actual intacto. El bloque de ubicación se revalida todo-o-nada
 * contra el estado RESULTANTE (valores nuevos combinados con los ya guardados), no solo contra
 * los campos presentes en este request -- ver {@code ClienteService#actualizar}.
 */
public record ActualizarClienteRequest(

        @Size(max = 255)
        String nombre,

        @Size(max = 2)
        String tipoIdentificacion,

        @Size(max = 20)
        String numeroIdentificacion,

        @Size(max = 6)
        String codigoActividad,

        @Size(max = 1)
        String codigoProvincia,

        @Size(max = 2)
        String canton,

        @Size(max = 2)
        String distrito,

        @Size(max = 300)
        String otrasSenas,

        @Size(max = 20)
        String telefono,

        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email,

        Boolean requiereFacturaElectronica) {
}
