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
import java.util.List;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import cr.ac.fractall.seguridad.dto.LogoutRequest;
import cr.ac.fractall.seguridad.dto.MfaCodigoRequest;
import cr.ac.fractall.seguridad.dto.RecuperarPasswordRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.dto.RestablecerPasswordRequest;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.Base32Codec;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.seguridad.servicio.TotpService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de punta a punta de los 5 nuevos endpoints de auth:
 * {@code GET /auth/mis-empresas}, {@code GET /auth/perfil}, {@code POST /auth/logout},
 * {@code POST /auth/recuperar-password}, y {@code POST /auth/restablecer-password}.
 *
 * <p>Misma infraestructura de Testcontainers (Postgres + Vault + stub de Resend) que las
 * clases {@code AuthFlujoRegistroYVerificacionTest} y {@code AuthFlujoLoginYTenantTest}.
 * Todo login en este árbol pasa por el flujo MFA porque el rol por defecto es ADMIN_EMPRESA.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlujoNuevosEndpointsTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final Pattern PATRON_TOKEN_VERIFICACION = Pattern.compile("token=([\\w-]+)");
    // El token de recuperación de password es base64url — más amplio que \w
    private static final Pattern PATRON_TOKEN_RESET = Pattern.compile("token=([\\w-]+)");

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

                path "transit/encrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SesionRefreshTokenRepository sesionRefreshTokenRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TotpService totpService;

    @BeforeEach
    void limpiarCapturasDelStub() {
        CUERPOS_CAPTURADOS.clear();
    }

    private static String emailUnico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID() + "@fractall.test";
    }

    /** Registra, verifica email y completa MFA. Devuelve los tokens de sesión. */
    private record SesionTokens(String accessToken, String refreshToken, UUID empresaId) {}

    private SesionTokens registrarVerificarYAutenticar(String prefijo, String password) throws Exception {
        String email = emailUnico(prefijo);
        RegistroRequest registro = new RegistroRequest(
                "Persona " + prefijo, email, password, "Empresa " + prefijo + " S.A.");

        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        // Verificar email
        String cuerpoCapturado = CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS);
        assertThat(cuerpoCapturado).isNotNull();
        Matcher matcher = PATRON_TOKEN_VERIFICACION.matcher(cuerpoCapturado);
        assertThat(matcher.find()).isTrue();
        String tokenVerificacion = matcher.group(1);

        mockMvc.perform(get("/auth/verificar-email").param("token", tokenVerificacion))
                .andExpect(status().isOk());

        // Login → MFA pendiente
        String loginBody = objectMapper.writeValueAsString(
                new cr.ac.fractall.seguridad.dto.LoginRequest(email, password));
        String loginResp = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String tokenMfaPendiente = objectMapper.readTree(loginResp).get("tokenMfaPendiente").asText();

        // Enrolar MFA
        String enrolamientoResp = mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secretoBase32 = objectMapper.readTree(enrolamientoResp).get("secretoBase32").asText();
        String codigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));

        // Confirmar MFA → tokens completos
        String confirmResp = mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(codigo))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode nodo = objectMapper.readTree(confirmResp);
        return new SesionTokens(
                nodo.get("accessToken").asText(),
                nodo.get("refreshToken").asText(),
                UUID.fromString(nodo.get("empresaId").asText()));
    }

    // ---- Task 4.5 tests ----

    @Test
    void misEmpresasRetorna401SinJwt() throws Exception {
        mockMvc.perform(get("/auth/mis-empresas"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void perfilRetorna401SinJwt() throws Exception {
        mockMvc.perform(get("/auth/perfil"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRetorna400SinRefreshTokenEnCuerpo() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("logout-400", "claveSegura123");

        // Missing refreshToken field → 400
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + sesion.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRetorna401SinJwt() throws Exception {
        LogoutRequest logoutReq = new LogoutRequest("cualquier-token", false);
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevocaTokenYRetornaMensajeOk() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("logout-ok", "claveSegura123");

        LogoutRequest logoutReq = new LogoutRequest(sesion.refreshToken(), false);
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + sesion.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").exists());

        // El refresh token ya debe estar revocado (no encontrable como activo)
        TenantContextDescartable.ejecutar((Runnable) () -> {
            List<cr.ac.fractall.seguridad.modelo.SesionRefreshToken> activos =
                    sesionRefreshTokenRepository.findByUsuarioIdAndRevocadoFalse(
                            jwtService.extraerUsuarioId(sesion.accessToken()));
            assertThat(activos).isEmpty();
        });
    }

    @Test
    void misEmpresasRetornaListaConEmpresaDelUsuario() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("mis-empresas", "claveSegura123");

        mockMvc.perform(get("/auth/mis-empresas")
                        .header("Authorization", "Bearer " + sesion.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].empresaId").exists())
                .andExpect(jsonPath("$[0].razonSocial").exists())
                .andExpect(jsonPath("$[0].rolCodigo").value("ADMIN_EMPRESA"));
    }

    @Test
    void perfilRetornaDatosDelUsuarioAutenticado() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("perfil-ok", "claveSegura123");

        mockMvc.perform(get("/auth/perfil")
                        .header("Authorization", "Bearer " + sesion.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").exists())
                .andExpect(jsonPath("$.nombre").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.empresaActiva").exists())
                .andExpect(jsonPath("$.permisos").isArray());
    }

    @Test
    void recuperarPasswordSiempreRetorna200ConMensajeGenerico() throws Exception {
        RecuperarPasswordRequest req = new RecuperarPasswordRequest(emailUnico("no-existe"));

        // Usar IP única para este test y no afectar el rate limiter de otros tests
        String resp = mockMvc.perform(post("/auth/recuperar-password")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode nodo = objectMapper.readTree(resp);
        assertThat(nodo.get("mensaje").asText()).isNotBlank();
    }

    @Test
    void recuperarPasswordParaEmailConocidoVerificadoEnviaCorreo() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("recuperar-known", "claveSegura123");
        CUERPOS_CAPTURADOS.clear();

        String email = TenantContextDescartable.ejecutar(() ->
                usuarioRepository.findById(jwtService.extraerUsuarioId(sesion.accessToken()))
                        .orElseThrow().getEmail());

        // IP única por test para evitar colisión con el rate limiter
        RecuperarPasswordRequest req = new RecuperarPasswordRequest(email);
        mockMvc.perform(post("/auth/recuperar-password")
                        .header("X-Forwarded-For", "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").exists());

        // Debe haber enviado correo con el token
        String cuerpoCapturado = CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS);
        assertThat(cuerpoCapturado).isNotNull().contains("restablecer-password");
    }

    @Test
    void restablecerPasswordConTokenValidoActualizaPassword() throws Exception {
        SesionTokens sesion = registrarVerificarYAutenticar("restablecer-ok", "claveVieja123");
        CUERPOS_CAPTURADOS.clear();

        String email = TenantContextDescartable.ejecutar(() ->
                usuarioRepository.findById(jwtService.extraerUsuarioId(sesion.accessToken()))
                        .orElseThrow().getEmail());

        // Solicitar recuperación con IP única
        RecuperarPasswordRequest recuperar = new RecuperarPasswordRequest(email);
        mockMvc.perform(post("/auth/recuperar-password")
                        .header("X-Forwarded-For", "10.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recuperar)))
                .andExpect(status().isOk());

        // Extraer token del correo capturado
        String cuerpoCapturado = CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS);
        assertThat(cuerpoCapturado).isNotNull();
        Matcher matcher = PATRON_TOKEN_RESET.matcher(cuerpoCapturado);
        assertThat(matcher.find()).isTrue();
        String tokenReset = matcher.group(1);

        // Restablecer
        RestablecerPasswordRequest restablecer = new RestablecerPasswordRequest(tokenReset, "nuevaClave456");
        mockMvc.perform(post("/auth/restablecer-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(restablecer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").exists());
    }

    @Test
    void restablecerPasswordConTokenInvalidoRetorna400() throws Exception {
        RestablecerPasswordRequest req = new RestablecerPasswordRequest(
                "token-que-nunca-existio-1234567890", "nuevaClave456");

        mockMvc.perform(post("/auth/restablecer-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").exists());
    }
}
