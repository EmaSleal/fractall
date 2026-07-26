package cr.ac.fractall.seguridad.dto;

import java.util.UUID;

/**
 * Resumen de una empresa/membresía activa del usuario autenticado -- usado tanto en
 * {@code GET /auth/mis-empresas} como en {@code GET /auth/perfil}.
 */
public record EmpresaResumenResponse(
        UUID empresaId,
        String razonSocial,
        String nombreComercial,
        String rolCodigo,
        String estadoMembresia) {
}
