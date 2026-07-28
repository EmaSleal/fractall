package cr.ac.fractall.catalogo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

        @Schema(description = "Full name of the client", example = "Empresa Ejemplo S.A.")
        @NotBlank
        @Size(max = 255)
        String nombre,

        @Schema(description = "ID type code: 01=Cédula Física, 02=Cédula Jurídica, 03=DIMEX, 04=NITE", example = "01")
        @NotBlank
        @Size(max = 2)
        String tipoIdentificacion,

        @Schema(description = "ID number (up to 20 digits)", example = "3101234567")
        @NotBlank
        @Size(max = 20)
        String numeroIdentificacion,

        @Schema(description = "Economic activity code (6 digits)", example = "620100")
        @Size(max = 6)
        String codigoActividad,

        @Schema(description = "Province code (1 digit). Must be provided together with canton, distrito and otrasSenas (all-or-none location block).", example = "1")
        @Size(max = 1)
        String codigoProvincia,

        @Schema(description = "Canton code (2 digits). Must be provided together with codigoProvincia, distrito and otrasSenas (all-or-none location block).", example = "01")
        @Size(max = 2)
        String canton,

        @Schema(description = "Distrito code (2 digits). Must be provided together with codigoProvincia, canton and otrasSenas (all-or-none location block).", example = "01")
        @Size(max = 2)
        String distrito,

        @Schema(description = "Additional address details. Must be provided together with codigoProvincia, canton and distrito (all-or-none location block).", example = "100m norte del parque central")
        @Size(max = 300)
        String otrasSenas,

        @Schema(description = "Contact phone number", example = "22345678")
        @Size(max = 20)
        String telefono,

        @Schema(description = "Contact email address", example = "contacto@empresa.com")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 255)
        String email,

        @Schema(description = "Whether the client requires electronic invoicing (defaults to true)", example = "true")
        Boolean requiereFacturaElectronica) {
}
