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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import cr.ac.fractall.seguridad.dto.LoginRequest;
import cr.ac.fractall.seguridad.dto.MfaCodigoRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.dto.SeleccionEmpresaRequest;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.Base32Codec;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.seguridad.servicio.TotpService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de punta a punta del batch final de la Fase 4 (sección 3.3 de
 * {@code arquitectura-facturacion-electronica-cr.md}): enrolamiento y verificación MFA/TOTP.
 *
 * <p>Cubre el criterio de salida de la Fase 4 completa ({@code plan-fases-release-1.md}):
 * "una persona se registra, verifica su correo, inicia sesión, y completa el enrolamiento MFA
 * de punta a punta" -- {@link #registroVerificacionLoginYEnrolamientoMfaDePuntaAPuntaCompletanElLoginYHabilitanMfa()}
 * es ese flujo completo. El resto de los tests cubre los rechazos (código incorrecto, token
 * de propósito equivocado en cada endpoint) exigidos junto con ese criterio.
 *
 * <p>Mismo bootstrap de Postgres + Vault vía Testcontainers y el mismo stub de Resend que
 * {@code AuthFlujoLoginYTenantTest}/{@code AuthFlujoRegistroYVerificacionTest} -- ver el
 * javadoc de esas clases para el porqué (no se extrae una superclase de test compartida por
 * la misma razón de aislamiento de batches que ya documentan esas clases).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlujoMfaTest {

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

    // Ver el comentario equivalente en AuthFlujoRegistroYVerificacionTest sobre por qué esta
    // instancia NO se inyecta vía @Autowired.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

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

    private record RegistroYVerificacion(UUID usuarioId, UUID empresaId, String email, String password) {
    }

    private RegistroYVerificacion registrarYVerificarUsuario(String prefijo, String password) throws Exception {
        String email = emailUnico(prefijo);
        RegistroRequest registro = new RegistroRequest("Persona " + prefijo, email, password, "Empresa " + prefijo + " S.A.");

        String cuerpoRespuesta = mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode nodo = objectMapper.readTree(cuerpoRespuesta);
        UUID usuarioId = UUID.fromString(nodo.get("usuarioId").asText());
        UUID empresaId = UUID.fromString(nodo.get("empresaId").asText());

        String cuerpoCapturado = CUERPOS_CAPTURADOS.poll(10, TimeUnit.SECONDS);
        assertThat(cuerpoCapturado).isNotNull();
        String tokenCrudo = extraerToken(cuerpoCapturado);

        mockMvc.perform(get("/auth/verificar-email").param("token", tokenCrudo))
                .andExpect(status().isOk());

        return new RegistroYVerificacion(usuarioId, empresaId, email, password);
    }

    private static String extraerToken(String cuerpoJson) {
        Matcher matcher = PATRON_TOKEN.matcher(cuerpoJson);
        assertThat(matcher.find()).as("El cuerpo capturado debe contener el enlace con el token: " + cuerpoJson).isTrue();
        return matcher.group(1);
    }

    /** Login completo hasta obtener el token "MFA pendiente" -- punto de partida común de casi todos los tests. */
    private String loginYObtenerTokenMfaPendiente(RegistroYVerificacion datos, boolean requiereEnrolamientoEsperado)
            throws Exception {
        LoginRequest login = new LoginRequest(datos.email(), datos.password());
        String cuerpoLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenMfaPendiente").exists())
                .andExpect(jsonPath("$.requiereEnrolamiento").value(requiereEnrolamientoEsperado))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpoLogin).get("tokenMfaPendiente").asText();
    }

    @Test
    void registroVerificacionLoginYEnrolamientoMfaDePuntaAPuntaCompletanElLoginYHabilitanMfa() throws Exception {
        // registro + verificación de correo + login (única membresía, ADMIN_EMPRESA).
        RegistroYVerificacion datos = registrarYVerificarUsuario("mfa-enrolar", "clave-segura-123");
        String tokenMfaPendiente = loginYObtenerTokenMfaPendiente(datos, true);

        // /auth/mfa/enrolar: genera y persiste el secreto, sin habilitar MFA todavía.
        String cuerpoEnrolamiento = mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrCodeBase64Png").exists())
                .andExpect(jsonPath("$.secretoBase32").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode enrolamiento = objectMapper.readTree(cuerpoEnrolamiento);
        String secretoBase32 = enrolamiento.get("secretoBase32").asText();
        byte[] qrPng = java.util.Base64.getDecoder().decode(enrolamiento.get("qrCodeBase64Png").asText());
        assertThat(qrPng).isNotEmpty();

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findById(datos.usuarioId()).orElseThrow();
            assertThat(usuario.isMfaHabilitado()).isFalse();
            assertThat(usuario.getMfaSecretCifrado()).isNotNull();
        });

        // Calcula el código TOTP vigente con el propio TotpService de producción, a partir del
        // secreto devuelto -- prueba el cableado end-to-end (la corrección del algoritmo en sí
        // ya está probada por separado en TotpServiceRfc6238Test).
        String codigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));

        // /auth/mfa/confirmar: código válido -> habilita MFA y completa el login.
        String cuerpoConfirmacion = mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(codigo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.empresaId").value(datos.empresaId().toString()))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(cuerpoConfirmacion).get("accessToken").asText();

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findById(datos.usuarioId()).orElseThrow();
            assertThat(usuario.isMfaHabilitado()).isTrue();
        });

        // El access token final SÍ autentica una ruta protegida genérica.
        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void mfaConfirmarConCodigoIncorrectoEsRechazadoYNoHabilitaMfa() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("mfa-confirmar-malo", "clave-segura-123");
        String tokenMfaPendiente = loginYObtenerTokenMfaPendiente(datos, true);

        mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest("000000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El código MFA no es válido o ya expiró."));

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findById(datos.usuarioId()).orElseThrow();
            assertThat(usuario.isMfaHabilitado()).isFalse();
        });
    }

    @Test
    void mfaVerificarFuncionaEnSegundoLoginTrasEnrolamientoPrevioYRechazaCodigoIncorrecto() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("mfa-verificar", "clave-segura-123");

        // Primer login: enrola y confirma para habilitar MFA.
        String tokenMfaPendienteInicial = loginYObtenerTokenMfaPendiente(datos, true);
        String secretoBase32 = objectMapper.readTree(mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendienteInicial))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("secretoBase32").asText();
        String primerCodigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));
        mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendienteInicial)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(primerCodigo))))
                .andExpect(status().isOk());

        // Segundo login: ya enrolado (mfaHabilitado = true) -> requiereEnrolamiento = false.
        String tokenMfaPendienteSegundo = loginYObtenerTokenMfaPendiente(datos, false);

        // Código incorrecto -> rechazado.
        mockMvc.perform(post("/auth/mfa/verificar")
                        .header("Authorization", "Bearer " + tokenMfaPendienteSegundo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest("000000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El código MFA no es válido o ya expiró."));

        // Código correcto -> access token + refresh token completos, igual que un login normal.
        String segundoCodigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));
        mockMvc.perform(post("/auth/mfa/verificar")
                        .header("Authorization", "Bearer " + tokenMfaPendienteSegundo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(segundoCodigo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void mfaEnrolarRechazaSiElUsuarioYaTieneMfaHabilitado() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("mfa-ya-habilitado", "clave-segura-123");
        String tokenMfaPendiente = loginYObtenerTokenMfaPendiente(datos, true);

        String secretoBase32 = objectMapper.readTree(mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("secretoBase32").asText();
        String codigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));
        mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(codigo))))
                .andExpect(status().isOk());

        // Nuevo login (mismo usuario, ya habilitado) -> nuevo token MFA pendiente, pero
        // /auth/mfa/enrolar debe rechazar re-enrolar sobre un usuario ya habilitado.
        String tokenMfaPendienteSegundo = loginYObtenerTokenMfaPendiente(datos, false);
        mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendienteSegundo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El MFA ya está habilitado para esta cuenta."));
    }

    @Test
    void tokenMfaPendienteEsRechazadoComoBearerTokenGenericoYComoTokenDeSeleccionDeTenant() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("mfa-token-generico", "clave-segura-123");
        String tokenMfaPendiente = loginYObtenerTokenMfaPendiente(datos, true);

        // Nunca debe autenticar una ruta protegida genérica (mismo chequeo generalizado que
        // ya rechaza el token de selección de tenant, ver AuthFlujoLoginYTenantTest).
        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isUnauthorized());

        // Tampoco debe aceptarse en /auth/seleccionar-tenant -- son tokens de propósito
        // distinto, a pesar de que ambos son "de alcance mínimo".
        SeleccionEmpresaRequest seleccion = new SeleccionEmpresaRequest(datos.empresaId());
        mockMvc.perform(post("/auth/seleccionar-tenant")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seleccion)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El token de selección de tenant no es válido o ya expiró."));
    }

    @Test
    void unTokenDeSeleccionDeTenantEsRechazadoEnLosEndpointsMfa() throws Exception {
        // Un token de selección de tenant construido directamente (mismo patrón que
        // AuthFlujoLoginYTenantTest#seleccionarTenantRechazaTokenAusenteYAccessTokenNormalUsadoEnSuLugar)
        // nunca debe aceptarse en los endpoints /auth/mfa/*, a pesar de compartir la
        // naturaleza de "token de alcance mínimo" con el token MFA pendiente.
        String tokenSeleccionTenant = jwtService.generarTokenSeleccionTenant(UUID.randomUUID());

        mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenSeleccionTenant))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El token de MFA pendiente no es válido o ya expiró."));

        mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenSeleccionTenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest("123456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El token de MFA pendiente no es válido o ya expiró."));

        mockMvc.perform(post("/auth/mfa/verificar")
                        .header("Authorization", "Bearer " + tokenSeleccionTenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest("123456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El token de MFA pendiente no es válido o ya expiró."));
    }
}
