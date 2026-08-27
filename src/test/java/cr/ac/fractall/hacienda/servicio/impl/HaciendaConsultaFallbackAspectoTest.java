package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

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
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException;
import cr.ac.fractall.secretos.SecretosKvService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Prueba de integración (Postgres + Vault reales vía Testcontainers, mismo bootstrap mínimo que
 * {@code ComprobanteHaciendaPollingScheduledJobTest} -- ver su javadoc) del aspecto REAL de
 * Resilience4j alrededor de {@link HaciendaComprobanteApiServiceImpl#consultarComprobante}.
 *
 * <p>A diferencia de {@code HaciendaComprobanteApiServiceImplTest} (instancia directa, sin proxy),
 * este test necesita un {@code ApplicationContext} real: la clasificación por causa introducida en
 * PR3 (ver {@link HaciendaConfiguracionException}/{@code HaciendaComunicacionException}) solo tiene
 * sentido para el job de sondeo si esas excepciones ATRAVIESAN el aspecto {@code @CircuitBreaker} de
 * {@code consultarComprobante} -- y antes de este PR eso era imposible: el fallback declaraba su
 * tercer parámetro como {@code Throwable}, y Resilience4j hace matching de {@code FallbackMethod}
 * por el TIPO DECLARADO, así que interceptaba absolutamente cualquier excepción, no solo
 * {@code CallNotPermittedException} (circuito abierto) -- ver el hallazgo bloqueante en el design.
 *
 * <p>Prueba (a): con el circuito forzado a {@code OPEN} vía {@link CircuitBreakerRegistry} (mismo
 * bean singleton que usa el aspecto en producción, cacheado por nombre), el fallback SIGUE
 * interceptando -- la propia intención documentada del método (ver su javadoc en la clase bajo
 * prueba) sigue funcionando con la firma más específica.
 *
 * <p>Prueba (b): con el circuito CERRADO (comportamiento por defecto), forzar que
 * {@code consultarComprobante} lance {@link HaciendaConfiguracionException} (vía
 * {@code obtenerPassword} -- se mockea {@link SecretosKvService} para devolver
 * {@code Optional.empty()}, mismo enfoque que documenta el design, evita cualquier llamada HTTP
 * real) demuestra que esa excepción ahora SÍ se propaga al llamador en vez de perderse como un DTO
 * {@code PROCESANDO} -- la prueba de que la clasificación de PR3 llega viva hasta el job (PR5).
 *
 * <p>{@code resilience4j.retry.instances.haciendaAPI.max-attempts=1} se fija solo para este test
 * (vía {@code @DynamicPropertySource}, no toca {@code application.yaml}): sin esto,
 * {@code @Retry(name = "haciendaAPI")} (también presente en {@code consultarComprobante},
 * configuración por defecto de Resilience4j: reintenta cualquier excepción 3 veces) reintentaría
 * innecesariamente la prueba (b) antes de agotar los intentos y propagar -- el resultado final
 * sería el mismo, pero mucho más lento y menos determinista sobre cuántas veces se invoca el mock
 * de Vault.
 */
@Testcontainers
@SpringBootTest
class HaciendaConsultaFallbackAspectoTest {

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

        registry.add("resilience4j.retry.instances." + CIRCUIT_BREAKER_NAME + ".max-attempts", () -> "1");
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

    private UUID credencialId;

    @BeforeEach
    void setUp() {
        // UUID de descarte solo para abrir el EntityManager que crea Usuario/Empresa/
        // CredencialHacienda (ninguna de las tres es @TenantId) -- mismo patrón que
        // ComprobanteHaciendaPollingScheduledJobTest#setUp().
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba fallback Hacienda");
        usuario.setEmail("usuario-fallback-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa fallback Hacienda S.A.");
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
        TenantContext.clear();
    }

    @Test
    void cuandoElCircuitoEstaAbiertoElFallbackSigueInterceptandoYDevuelveProcesando() {
        circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).transitionToOpenState();

        // credencialId aleatorio a propósito: el chequeo de permiso del CircuitBreaker ocurre
        // ANTES de ejecutar el cuerpo del método decorado, así que ni siquiera llega a
        // obtenerCredencial -- lo que se prueba aquí es exclusivamente que el fallback sigue
        // siendo invocado cuando el circuito está abierto, con la firma más específica.
        RespuestaHaciendaDTO respuesta = haciendaComprobanteApiService.consultarComprobante(
                "clave-circuito-abierto", UUID.randomUUID());

        assertThat(respuesta.getCodigoMensaje()).isEqualTo(MensajeHacienda.PROCESANDO);
        assertThat(respuesta.getIndicadorEstado()).isEqualTo("circuit-breaker-open");
        assertThat(respuesta.getDebeReintentar()).isTrue();
    }

    @Test
    void cuandoConsultarComprobanteLanzaHaciendaConfiguracionExceptionYaNoLaInterceptaElFallback() {
        when(secretosKvService.leerSecreto(any(), any())).thenReturn(Optional.empty());

        // Antes de este PR, el fallback (Throwable) interceptaba esta excepción tipada tal como
        // cualquier otra y devolvía un RespuestaHaciendaDTO PROCESANDO -- el hallazgo bloqueante
        // del design. Con la firma angostada a CallNotPermittedException, el circuito sigue
        // cerrado (no se fuerza a OPEN en esta prueba), así que Resilience4j no encuentra un
        // fallback que matchee y la excepción SÍ llega al llamador.
        assertThatThrownBy(() -> haciendaComprobanteApiService.consultarComprobante("clave-config", credencialId))
                .isInstanceOf(HaciendaConfiguracionException.class);
    }
}
