package cr.ac.fractall.tenant;

import java.util.UUID;

/**
 * Contexto de tenant (empresa) del hilo de ejecución actual.
 *
 * <p>Deliberadamente desacoplado de {@code SecurityContextHolder}: ese acoplamiento
 * funciona en el flujo HTTP normal, pero se rompe en procesamiento asíncrono y jobs
 * programados. El componente que puebla este contexto por request ({@code JwtTenantFilter})
 * es responsabilidad de una fase posterior (Fase 2), fuera de alcance aquí.
 *
 * <p>Ver sección 5.2 de {@code arquitectura-facturacion-electronica-cr.md}.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> EMPRESA_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID empresaId) {
        EMPRESA_ID.set(empresaId);
    }

    public static UUID get() {
        return EMPRESA_ID.get();
    }

    public static void clear() {
        EMPRESA_ID.remove();
    }
}
