package cr.ac.fractall.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Punto de entrada para operaciones JPA que se ejecutan fuera de cualquier request que ya
 * traiga un tenant resuelto -- típicamente los endpoints públicos de {@code /auth/**}
 * (registro, verificación de correo, reenvío) y jobs {@code @Scheduled}, ninguno de los
 * cuales pasa por {@link JwtTenantFilter} con un JWT válido.
 *
 * <p>{@link EmpresaTenantIdentifierResolver} falla de forma cerrada para CUALQUIER entidad,
 * tenga o no columna {@code @TenantId}: Hibernate resuelve el tenant actual al abrir
 * cualquier {@code EntityManager} de este {@code SessionFactory} (ver el comentario de
 * {@code AislamientoMultiTenantTest#setUp()}). Como {@code JpaTransactionManager} abre el
 * {@code EntityManager} en {@code doBegin()}, ANTES de invocar el cuerpo del método
 * {@code @Transactional}, el valor debe fijarse en el llamador -- fijarlo dentro del propio
 * método transaccional llega demasiado tarde.
 *
 * <p>El UUID usado es descartable: {@code Usuario}, {@code Empresa}, {@code UsuarioEmpresa},
 * {@code UsuarioToken} y {@code ColaReintentoEmail} no extienden {@code TenantAwareEntity}
 * (no tienen columna {@code empresa_id} vía {@code @TenantId}), así que el valor en sí nunca
 * se usa para filtrar nada -- solo existe para satisfacer el chequeo fail-closed del
 * resolutor.
 */
public final class TenantContextDescartable {

    private TenantContextDescartable() {
    }

    public static <T> T ejecutar(Supplier<T> operacion) {
        TenantContext.set(UUID.randomUUID());
        try {
            return operacion.get();
        } finally {
            TenantContext.clear();
        }
    }

    public static void ejecutar(Runnable operacion) {
        TenantContext.set(UUID.randomUUID());
        try {
            operacion.run();
        } finally {
            TenantContext.clear();
        }
    }
}
