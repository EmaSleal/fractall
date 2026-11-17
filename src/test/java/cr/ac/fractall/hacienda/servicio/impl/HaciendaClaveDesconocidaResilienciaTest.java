package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.hacienda.servicio.HaciendaClaveDesconocidaException;
import cr.ac.fractall.hacienda.servicio.HaciendaComunicacionException;
import cr.ac.fractall.secretos.SecretosKvService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * T-008 (ADR-063, criterio 10): {@link HaciendaClaveDesconocidaException} no se reintenta con
 * {@code @Retry} ni cuenta como fallo del circuit breaker {@code haciendaAPI}. Usa el bean REAL y
 * proxificado de {@link HaciendaComprobanteApiService} (con la configuración de Resilience4j de
 * {@code application.yaml}: 3 intentos por defecto) y una intercepción de la capa HTTP que cuenta
 * los GET de consulta, sin tocar la red.
 *
 * <p>El caso de control (HTTP 500 -&gt; {@link HaciendaComunicacionException}) demuestra que el
 * aspecto está activo: se reintenta 3 veces y el circuito registra fallos.
 */
@Testcontainers
@SpringBootTest
@Import(HaciendaClaveDesconocidaResilienciaTest.HttpInterceptadoConfig.class)
class HaciendaClaveDesconocidaResilienciaTest {

    /** Respuesta que debe dar el GET de consulta; los POST de token siempre responden OK. */
    static final class ContadorHttpHacienda {
        final AtomicInteger consultas = new AtomicInteger();
        volatile HttpStatus estadoConsulta = HttpStatus.BAD_REQUEST;
        volatile String causa = "El comprobante [clave-x] no ha sido recibido.";

        void reiniciar() {
            consultas.set(0);
            estadoConsulta = HttpStatus.BAD_REQUEST;
            causa = "El comprobante [clave-x] no ha sido recibido.";
        }
    }

    @TestConfiguration
    static class HttpInterceptadoConfig {
        @Bean
        ContadorHttpHacienda contadorHttpHacienda() {
            return new ContadorHttpHacienda();
        }

        @Bean
        RestClientCustomizer interceptorHacienda(ContadorHttpHacienda contador) {
            ClientHttpRequestInterceptor interceptor = new ClientHttpRequestInterceptor() {
                @Override
                public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                        ClientHttpRequestExecution execution) throws IOException {
                    URI uri = request.getURI();
                    if (uri.getHost() == null || !uri.getHost().endsWith("comprobanteselectronicos.go.cr")) {
                        return execution.execute(request, body);
                    }
                    if (request.getMethod() == HttpMethod.POST) {
                        MockClientHttpResponse token = new MockClientHttpResponse(
                                """
                                {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                                """.getBytes(), HttpStatus.OK);
                        token.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        return token;
                    }
                    contador.consultas.incrementAndGet();
                    MockClientHttpResponse respuesta = new MockClientHttpResponse(new byte[0], contador.estadoConsulta);
                    HttpHeaders cabeceras = respuesta.getHeaders();
                    if (contador.estadoConsulta == HttpStatus.BAD_REQUEST) {
                        cabeceras.add("x-error-cause", contador.causa);
                    }
                    return respuesta;
                }
            };
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String CIRCUIT_BREAKER_NAME = "haciendaAPI";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();

        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);
    }

    private static void bootstrapAppRole() throws Exception {
        ejecutarVault("auth", "enable", "approle");
        ejecutarVault("secrets", "enable", "transit");
        ejecutarVault("write", "-f", "transit/keys/" + TRANSIT_KEY);

        String politicaHcl = """
                path "secret/data/empresas/*" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/keys/%s" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/datakey/plaintext/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/decrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
        ExecResult resultadoPolitica = VAULT.execInContainer(
                "sh", "-c",
                "cat <<'EOF' | vault policy write " + POLICY_NAME + " -\n" + politicaHcl + "EOF");
        assertThat(resultadoPolitica.getExitCode()).as(resultadoPolitica.getStderr()).isZero();

        ejecutarVault("write", "auth/approle/role/" + APPROLE_NAME,
                "token_policies=" + POLICY_NAME,
                "token_ttl=1h",
                "token_max_ttl=4h",
                "secret_id_ttl=0",
                "token_num_uses=0");

        roleId = ejecutarVault("read", "-field=role_id", "auth/approle/role/" + APPROLE_NAME + "/role-id")
                .getStdout().trim();
        secretId = ejecutarVault("write", "-field=secret_id", "-f", "auth/approle/role/" + APPROLE_NAME + "/secret-id")
                .getStdout().trim();
    }

    private static ExecResult ejecutarVault(String... comandoVault) throws Exception {
        String[] comandoCompleto = new String[comandoVault.length + 1];
        comandoCompleto[0] = "vault";
        System.arraycopy(comandoVault, 0, comandoCompleto, 1, comandoVault.length);

        ExecResult resultado = VAULT.execInContainer(comandoCompleto);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        return resultado;
    }

    @Autowired
    private HaciendaComprobanteApiService haciendaComprobanteApiService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @MockitoBean
    private SecretosKvService secretosKvService;

    @Autowired
    private ContadorHttpHacienda contadorHttp;

    private UUID credencialId;

    @BeforeEach
    void setUp() {
        // UUID de descarte solo para abrir el EntityManager que crea Usuario/Empresa/
        // CredencialHacienda (ninguna de las tres es @TenantId) -- mismo patrón que
        // ComprobanteHaciendaPollingScheduledJobTest#setUp().
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba resiliencia Hacienda");
        usuario.setEmail("usuario-resiliencia-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa resiliencia Hacienda S.A.");
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(usuario.getId());
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(empresa);

        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresa.getId());
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda("usuario@hacienda.test");
        credencial.setCredencialReferencia(
                "secret/data/empresas/" + empresa.getId() + "/hacienda/sandbox/password");
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(usuario.getId());
        credencial = credencialHaciendaRepository.save(credencial);
        credencialId = credencial.getId();
    }

    @AfterEach
    void tearDown() {
        // El CircuitBreaker "haciendaAPI" es un singleton compartido (mismo bean para
        // enviarComprobante/consultarComprobante) -- forzarlo a OPEN en una prueba y no
        // reiniciarlo dejaría el circuito abierto para la prueba siguiente del mismo contexto
        // de Spring (cacheado entre métodos @Test de esta clase).
        circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).reset();
        contadorHttp.reiniciar();
        TenantContext.clear();
    }

    @Test
    void laClaveDesconocidaHaceUnaSolaLlamadaHttpYNoRegistraFalloEnElCircuito() {
        when(secretosKvService.leerSecreto(any(), any())).thenReturn(Optional.of("clave-secreta"));
        var metricas = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).getMetrics();
        int fallosAntes = metricas.getNumberOfFailedCalls();

        assertThatThrownBy(() -> haciendaComprobanteApiService.consultarComprobante("clave-x", credencialId))
                .isInstanceOf(HaciendaClaveDesconocidaException.class);

        assertThat(contadorHttp.consultas.get()).as("sin reintentos de @Retry").isEqualTo(1);
        assertThat(metricas.getNumberOfFailedCalls()).as("el circuito no acumula fallos").isEqualTo(fallosAntes);
    }

    @Test
    void controlUnaFallaDeComunicacionSiSeReintentaYSiRegistraFalloEnElCircuito() {
        when(secretosKvService.leerSecreto(any(), any())).thenReturn(Optional.of("clave-secreta"));
        contadorHttp.estadoConsulta = HttpStatus.INTERNAL_SERVER_ERROR;
        var metricas = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).getMetrics();
        int fallosAntes = metricas.getNumberOfFailedCalls();

        assertThatThrownBy(() -> haciendaComprobanteApiService.consultarComprobante("clave-x", credencialId))
                .isInstanceOf(HaciendaComunicacionException.class);

        assertThat(contadorHttp.consultas.get()).as("el aspecto @Retry está activo").isGreaterThan(1);
        assertThat(metricas.getNumberOfFailedCalls()).isGreaterThan(fallosAntes);
    }
}
