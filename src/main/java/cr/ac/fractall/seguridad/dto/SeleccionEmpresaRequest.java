package cr.ac.fractall.seguridad.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Cuerpo compartido por {@code POST /auth/seleccionar-tenant} y {@code POST /auth/cambiar-tenant}. */
public record SeleccionEmpresaRequest(

        @NotNull(message = "El empresaId es obligatorio")
        UUID empresaId) {
}
