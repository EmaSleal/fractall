package cr.ac.fractall.seguridad.dto;

import java.util.UUID;

public record RegistroResponse(UUID usuarioId, UUID empresaId, String mensaje) {
}
