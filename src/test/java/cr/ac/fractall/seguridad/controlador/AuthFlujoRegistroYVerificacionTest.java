package cr.ac.fractall.seguridad.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import cr.ac.fractall.empresa.Empresa;
import cr.ac.fractall.empresa.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.ReenviarVerificacionRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de punta a punta de los 3 endpoints de la Fase 4 (sección 3.1 de
 * {@code arquitectura-facturacion-electronica-cr.md}): registro transaccional, verificación
 * de correo, y reenvío con límite de tasa.
 *
 * <p>Bootstrapea Postgres y Vault vía Testcontainers, igual que
 * {@code SecretosVaultClienteTest} -- {@code VaultConfig} conecta de forma ansiosa al
 * arrancar el contexto, así que cualquier {@code @SpringBootTest} completo necesita un
 * Vault real alcanzable, sin importar si esta prueba usa MFA/cifrado o no.
 *
 * <p>Resend se sustituye por un {@link HttpServer} embebido (JDK puro, sin dependencia
 * nueva) que captura el cuerpo JSON exacto de cada solicitud saliente -- el token crudo de
 * verificación nunca se devuelve en la respuesta HTTP del registro (solo se envía por
 * correo), así que se extrae del HTML capturado para completar el flujo de verificación.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlujoRegistroYVerificacionTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final Pattern PATRON_TOKEN = Pattern.compile("token=([\\w-]+)");

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;
    private static HttpServer resendStub;
    private static final BlockingQueue<String> CUERPOS_CAPTURADOS = new LinkedBlockingQueue<>();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();
        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);

        resendStub = iniciarResendStub();
        registry.add("application.notificaciones.resend.api-key", () -> "test-api-key-no-real");
        registry.add("application.notificaciones.resend.remitente", () -> "onboarding@resend.dev");
        registry.add("application.notificaciones.resend.api-url",
                () -> "http://localhost:" + resendStub.getAddress().getPort() + "/emails");
    }

    @AfterAll
    static void detenerStub() {
        if (resendStub != null) {
            resendStub.stop(0);
        }
    }

    private static HttpServer iniciarResendStub() throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(0), 0);
        servidor.createContext("/emails", exchange -> {
            String cuerpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CUERPOS_CAPTURADOS.offer(cuerpo);
            byte[] respuesta = "{\"id\":\"stub-email-id\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respuesta.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(respuesta);
            }
        });
        servidor.start();
        return servidor;
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

    // Instancia propia, NO inyectada: Spring Boot 4.1 autoconfigura por defecto un
    // ObjectMapper de Jackson 3 (tools.jackson.databind), no de la clásica
    // com.fasterxml.jackson.databind usada aquí (la que sí llega al classpath de pruebas de
    // forma transitiva vía jjwt-jackson) -- @Autowired de este tipo no encuentra bean.
    // Como aquí solo se serializan los DTOs de request propios, no hace falta el bean de
    // Spring ni que coincida con lo que usa MVC internamente para (de)serializar.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @Autowired
    private RolRepository rolRepository;

    // CUERPOS_CAPTURADOS es estático (compartido por el stub HTTP embebido, único por
    // clase), así que sin drenarlo entre pruebas, un test que no consume su propia captura
    // (ej. registroCreaUsuarioEmpresaYMembresiaAtomicamenteConRolAdminEmpresa) deja un
    // sobrante que un test posterior podría consumir por error, en lugar del suyo propio.
    @BeforeEach
    void limpiarCapturasDelStub() {
        CUERPOS_CAPTURADOS.clear();
    }

    private static String emailUnico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID() + "@fractall.test";
    }

    @Test
    void registroCreaUsuarioEmpresaYMembresiaAtomicamenteConRolAdminEmpresa() throws Exception {
        String email = emailUnico("registro");
        RegistroRequest request = new RegistroRequest("Persona de Prueba", email, "clave-segura-123", "Empresa de Prueba S.A.");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").exists())
                .andExpect(jsonPath("$.empresaId").exists());

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findByEmail(email).orElseThrow();
            assertThat(usuario.getEstado()).isEqualTo("PENDIENTE_VERIFICACION");
            assertThat(usuario.isEmailVerificado()).isFalse();
            assertThat(usuario.getPasswordHash()).isNotEqualTo("clave-segura-123");

            Empresa empresa = empresaRepository.findById(
                    empresaRepository.findAll().stream()
                            .filter(e -> usuario.getId().equals(e.getCreadoPor()))
                            .findFirst().orElseThrow().getId()).orElseThrow();
            assertThat(empresa.getRazonSocial()).isEqualTo("Empresa de Prueba S.A.");

            Rol rolAdminEmpresa = rolRepository.findByCodigo("ADMIN_EMPRESA").orElseThrow();
            UsuarioEmpresa membresia = usuarioEmpresaRepository.findAll().stream()
                    .filter(ue -> ue.getUsuarioId().equals(usuario.getId()) && ue.getEmpresaId().equals(empresa.getId()))
                    .findFirst().orElseThrow();
            assertThat(membresia.getRolId()).isEqualTo(rolAdminEmpresa.getId());
            assertThat(membresia.getEstado()).isEqualTo("ACTIVO");
        });
    }

    @Test
    void registrarConCorreoDuplicadoEsRechazado() throws Exception {
        String email = emailUnico("duplicado");
        RegistroRequest request = new RegistroRequest("Persona Original", email, "clave-segura-123", "Empresa Original S.A.");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void verificarEmailConTokenValidoActivaLaCuentaYMarcaElTokenComoUsado() throws Exception {
        String email = emailUnico("verificacion");
        RegistroRequest request = new RegistroRequest("Persona a Verificar", email, "clave-segura-123", "Empresa a Verificar S.A.");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        String cuerpoCapturado = CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS);
        assertThat(cuerpoCapturado).isNotNull();
        assertThat(cuerpoCapturado).contains(email);
        String tokenCrudo = extraerToken(cuerpoCapturado);

        mockMvc.perform(get("/auth/verificar-email").param("token", tokenCrudo))
                .andExpect(status().isOk());

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findByEmail(email).orElseThrow();
            assertThat(usuario.isEmailVerificado()).isTrue();
            assertThat(usuario.getEstado()).isEqualTo("ACTIVA");
        });

        // El mismo token, usado una segunda vez, debe rechazarse (usado=true la primera vez).
        mockMvc.perform(get("/auth/verificar-email").param("token", tokenCrudo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verificarEmailConTokenInvalidoEsRechazadoConMensajeGenerico() throws Exception {
        mockMvc.perform(get("/auth/verificar-email").param("token", "token-que-nunca-existio-1234567890"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El enlace de verificación no es válido o ya expiró."));
    }

    @Test
    void reenvioVerificacionRespetaElLimiteDeTasaYNoEnviaUnSegundoCorreoDeInmediato() throws Exception {
        String email = emailUnico("reenvio");
        RegistroRequest request = new RegistroRequest("Persona de Reenvio", email, "clave-segura-123", "Empresa de Reenvio S.A.");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // El registro ya generó y envió un token de VERIFICACION_EMAIL hace instantes --
        // un reenvío inmediato debe caer dentro de la ventana de 5 minutos y NO disparar un
        // segundo envío (ni por email ni por IP).
        assertThat(CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS)).isNotNull();

        ReenviarVerificacionRequest reenvio = new ReenviarVerificacionRequest(email);
        mockMvc.perform(post("/auth/reenviar-verificacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reenvio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(
                        "Si el correo existe en nuestro sistema y aún no ha sido verificado, "
                                + "se enviará un nuevo enlace de verificación en unos minutos."));

        assertThat(CUERPOS_CAPTURADOS.poll(2, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void reenvioVerificacionParaCorreoInexistenteRespondeMensajeGenericoSinRevelarNada() throws Exception {
        ReenviarVerificacionRequest reenvio = new ReenviarVerificacionRequest(emailUnico("no-existe"));

        mockMvc.perform(post("/auth/reenviar-verificacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reenvio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(
                        "Si el correo existe en nuestro sistema y aún no ha sido verificado, "
                                + "se enviará un nuevo enlace de verificación en unos minutos."));

        assertThat(CUERPOS_CAPTURADOS.poll(2, TimeUnit.SECONDS)).isNull();
    }

    private static String extraerToken(String cuerpoJson) {
        Matcher matcher = PATRON_TOKEN.matcher(cuerpoJson);
        assertThat(matcher.find()).as("El cuerpo capturado debe contener el enlace con el token: " + cuerpoJson).isTrue();
        return matcher.group(1);
    }
}
