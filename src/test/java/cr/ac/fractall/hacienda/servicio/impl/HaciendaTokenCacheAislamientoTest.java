package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import cr.ac.fractall.config.CacheConfig;
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.hacienda.dto.TokenHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.secretos.SecretosKvService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba dedicada del aislamiento del caché {@code haciendaToken}
 * ({@code @Cacheable(value = "haciendaToken", key = "#credencialId")} en
 * {@link HaciendaComprobanteApiServiceImpl#autenticar}) entre dos credenciales de empresas
 * distintas -- mismo espíritu que {@code AislamientoMultiTenantTest}/
 * {@code ContadorConsecutivoAislamientoTest}, pero para un caché en memoria en vez de una fila de
 * base de datos: {@link CredencialHacienda} NO es {@code TenantAwareEntity} (no hay filtro de
 * Hibernate que aplique aquí), así que la única garantía de aislamiento por tenant es que la
 * llave del caché sea el {@code UUID} de la credencial -- que, por construcción, ya identifica
 * una única empresa+ambiente (ver el javadoc de
 * {@link cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService}).
 *
 * <p>A diferencia de {@code HaciendaComprobanteApiServiceImplTest} (que instancia la clase
 * directamente, sin proxy), este test SÍ necesita un {@code ApplicationContext} real con
 * {@code @EnableCaching}: sin un proxy de Spring de por medio, {@code @Cacheable} simplemente no
 * se aplica y esta prueba no probaría nada. El bean se construye igual con el constructor de
 * paquete (RestClient ya enlazado a {@link MockRestServiceServer}, mismo motivo que en el otro
 * test): la llamada a {@code autenticar} se hace siempre desde AFUERA de la clase (a través del
 * bean inyectado), así que el problema de auto-invocación que motiva el parámetro {@code self}
 * (ver el javadoc de {@code HaciendaComprobanteApiServiceImpl}) no aplica en este escenario en
 * particular -- este test cubre el aislamiento del caché, no la propagación de {@code self} a
 * las llamadas internas de {@code enviarComprobante}/{@code consultarComprobante}/etc.
 */
@SpringJUnitConfig(HaciendaTokenCacheAislamientoTest.Config.class)
class HaciendaTokenCacheAislamientoTest {

    static final String SANDBOX_TOKEN_URL = "https://idp.test/token";
    static final String SANDBOX_API_URL = "https://api-sandbox.test";

    static MockRestServiceServer servidorMock;

    @Configuration
    @EnableCaching
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.CACHE_HACIENDA_TOKEN);
        }

        @Bean
        CredencialHaciendaRepository credencialHaciendaRepository() {
            return mock(CredencialHaciendaRepository.class);
        }

        @Bean
        EmpresaRepository empresaRepository() {
            return mock(EmpresaRepository.class);
        }

        @Bean
        SecretosKvService secretosKvService() {
            return mock(SecretosKvService.class);
        }

        @Bean
        HaciendaComprobanteApiService haciendaComprobanteApiService(
                CredencialHaciendaRepository credencialHaciendaRepository,
                EmpresaRepository empresaRepository,
                SecretosKvService secretosKvService) {
            RestClient.Builder builder = RestClient.builder();
            servidorMock = MockRestServiceServer.bindTo(builder).build();
            RestClient restClient = builder.build();
            ObjectMapper objectMapper = JsonMapper.builder().build();

            return new HaciendaComprobanteApiServiceImpl(
                    credencialHaciendaRepository,
                    empresaRepository,
                    secretosKvService,
                    restClient,
                    objectMapper,
                    new org.springframework.cache.concurrent.ConcurrentMapCacheManager(
                            cr.ac.fractall.config.CacheConfig.CACHE_HACIENDA_TOKEN),
                    "api-stag",
                    SANDBOX_TOKEN_URL,
                    SANDBOX_API_URL,
                    "https://idp.test/token-prod",
                    "https://api.test");
        }
    }

    @Autowired
    private HaciendaComprobanteApiService servicio;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private SecretosKvService secretosKvService;

    private static CredencialHacienda credencialSandbox(UUID id, UUID empresaId, String usuario) {
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresaId);
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda(usuario);
        credencial.setCredencialReferencia("secret/data/empresas/" + empresaId + "/hacienda/sandbox/password");
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(UUID.randomUUID());
        return credencial;
    }

    private static String cuerpoToken(String accessToken) {
        return """
                {"access_token":"%s","refresh_token":"rt-%s","expires_in":3600,"token_type":"Bearer"}
                """.formatted(accessToken, accessToken);
    }

    @Test
    void autenticarCacheaPorCredencialSinCompartirTokenEntreEmpresas() {
        UUID credencialA = UUID.randomUUID();
        UUID empresaA = UUID.randomUUID();
        UUID credencialB = UUID.randomUUID();
        UUID empresaB = UUID.randomUUID();

        when(credencialHaciendaRepository.findById(credencialA))
                .thenReturn(Optional.of(credencialSandbox(credencialA, empresaA, "usuario-empresa-a")));
        when(credencialHaciendaRepository.findById(credencialB))
                .thenReturn(Optional.of(credencialSandbox(credencialB, empresaB, "usuario-empresa-b")));
        when(secretosKvService.leerSecreto(eq(empresaA), any()))
                .thenReturn(Optional.of("password-empresa-a"));
        when(secretosKvService.leerSecreto(eq(empresaB), any()))
                .thenReturn(Optional.of("password-empresa-b"));

        // Exactamente 2 llamadas HTTP esperadas -- una por credencial. Si el caché no aislara
        // correctamente por #credencialId (p. ej. si la llave fuera solo #empresaId de forma
        // incorrecta, o si @Cacheable no se aplicara), la tercera llamada de este test (que
        // reutiliza credencialA) dispararía una TERCERA petición HTTP no esperada, y
        // MockRestServiceServer la rechazaría en el momento mismo de intentarla.
        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(content().string(Matchers.containsString("username=usuario-empresa-a")))
                .andRespond(withSuccess(cuerpoToken("AT-empresa-A"), MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(content().string(Matchers.containsString("username=usuario-empresa-b")))
                .andRespond(withSuccess(cuerpoToken("AT-empresa-B"), MediaType.APPLICATION_JSON));

        TokenHaciendaDTO tokenA1 = servicio.autenticar(credencialA);
        TokenHaciendaDTO tokenB = servicio.autenticar(credencialB);
        TokenHaciendaDTO tokenA2 = servicio.autenticar(credencialA);

        assertThat(tokenA1.accessToken()).isEqualTo("AT-empresa-A");
        assertThat(tokenB.accessToken()).isEqualTo("AT-empresa-B");
        // La clave: el segundo autenticar(credencialA) NO debe disparar HTTP (viene del caché) Y
        // debe seguir devolviendo el token de A, nunca el de B -- no hay fuga cruzada de tenant.
        assertThat(tokenA2.accessToken()).isEqualTo("AT-empresa-A");

        servidorMock.verify();
    }
}
