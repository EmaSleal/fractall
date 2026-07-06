package cr.ac.fractall.seguridad.dto;

/**
 * Respuesta de {@code POST /auth/login} cuando el usuario tiene 2+ empresas activas
 * (sección 3.2, punto 2): el token de alcance mínimo debe canjearse de inmediato contra
 * {@code POST /auth/seleccionar-tenant}.
 */
public record SeleccionTenantRequeridaResponse(String tokenSeleccionTenant) {
}
