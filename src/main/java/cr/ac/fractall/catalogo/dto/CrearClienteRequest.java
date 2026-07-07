package cr.ac.fractall.catalogo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /catalogo/clientes} (sección 4.11 de
 * {@code arquitectura-facturacion-electronica-cr.md}). El bloque de ubicación
 * ({@code codigoProvincia}/{@code canton}/{@code distrito}/{@code otrasSenas}) es todo-o-nada --
 * validado explícitamente en {@code ClienteService}, no delegado al {@code CHECK} de motor.
 */
public record CrearClienteRequest(

        @NotBlank
        @Size(max = 255)
        String nombre,

        @NotBlank
        @Size(max = 2)
        String tipoIdentificacion,

        @NotBlank
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
