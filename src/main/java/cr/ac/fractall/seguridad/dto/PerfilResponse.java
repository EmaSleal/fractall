package cr.ac.fractall.seguridad.dto;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta de {@code GET /auth/perfil}: datos del usuario autenticado, empresa activa y
 * permisos efectivos en esa empresa (sección 3.5 de la especificación).
 */
public record PerfilResponse(
        UUID usuarioId,
        String nombre,
        String email,
        boolean mfaHabilitado,
        EmpresaResumenResponse empresaActiva,
        List<String> permisos) {
}
