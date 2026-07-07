package cr.ac.fractall.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita {@code @Cacheable}/{@code @CacheEvict} para toda la aplicación (Fase 8, primer
 * consumidor: {@code HaciendaComprobanteApiServiceImpl#autenticar}, que cachea el token OAuth2
 * de Hacienda por credencial para no reautenticar en cada envío/consulta de comprobante).
 *
 * <p>{@link ConcurrentMapCacheManager} (in-memory, sin TTL propio) es intencionalmente el único
 * backend por ahora -- no hace falta Redis/Caffeine todavía porque un único nodo/JVM es
 * suficiente en este estado del proyecto. El token de Hacienda expira solo (ver
 * {@code TokenHaciendaDTO#expiresIn}); nada en este {@code CacheManager} lo invalida
 * proactivamente todavía -- eso queda como trabajo de una fase futura si se detecta que un token
 * cacheado sigue usándose después de expirar.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_HACIENDA_TOKEN = "haciendaToken";

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(CACHE_HACIENDA_TOKEN);
    }
}
