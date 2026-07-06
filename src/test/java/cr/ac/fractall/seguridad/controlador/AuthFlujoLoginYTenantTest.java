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
import java.time.LocalDateTime;
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

import cr.ac.fractall.empresa.Empresa;
import cr.ac.fractall.empresa.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.LoginRequest;
import cr.ac.fractall.seguridad.dto.MfaCodigoRequest;
import cr.ac.fractall.seguridad.dto.RefrescarTokenRequest;
import cr.ac.fractall.seguridad.dto.RegistroRequest;
import cr.ac.fractall.seguridad.dto.SeleccionEmpresaRequest;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.SesionRefreshToken;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.SesionRefreshTokenRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.Base32Codec;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.seguridad.servicio.TotpService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de punta a punta del segundo batch de la Fase 4 (sección 3.2 y 3.4
 * de {@code arquitectura-facturacion-electronica-cr.md}): login, bloqueo por fuerza bruta,
 * selección de tenant, cambio de tenant y refresh tokens.
 *
 * <p>Toda membresía creada en este árbol de pruebas es {@code ADMIN_EMPRESA} (el único rol
 * que {@code RegistroService} asigna hoy), así que MFA (sección 3.3, batch final de la Fase 4,
 * ver {@code AuthFlujoMfaTest}) es obligatorio en TODO login/selección de tenant exitosos --
 * de ahí {@link #completarMfaYObtenerTokens(String)}: cualquier test que antes obtenía un
 * access token directo de {@code /auth/login}/{@code /auth/seleccionar-tenant} ahora primero
 * recibe un token "MFA pendiente" y debe completar enrolamiento + confirmación para llegar al
 * mismo punto. La corrección de MFA en sí (vectores TOTP, cifrado del secreto, rechazo de
 * código incorrecto, etc.) se prueba en {@code AuthFlujoMfaTest}, no aquí -- este archivo
 * sigue enfocado en login/selección de tenant/cambio de tenant/refresh tokens.
 *
 * <p>Mismo bootstrap de Postgres + Vault vía Testcontainers y el mismo stub de Resend que
 * {@code AuthFlujoRegistroYVerificacionTest} -- ver el javadoc de esa clase para el porqué.
 *
 * <p>La ruta protegida usada para probar la corrección de seguridad de
 * {@code JwtAuthenticationFilter} es {@code GET /api/ping}, definida por {@code PingController}
 * al final de {@code SecurityConfigTest} (mismo árbol de pruebas, mismo component-scan de
 * {@code cr.ac.fractall} que arranca la aplicación real) -- no se agrega un segundo
 * controlador de prueba duplicado para el mismo propósito.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlujoLoginYTenantTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final Pattern PATRON_TOKEN = Pattern.compile("token=([\\w-]+)");
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";
    private static final String ESTADO_ACTIVO = "ACTIVO";

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
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @Autowired
    private RolRepository rolRepository;

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

    /**
     * Completa enrolamiento MFA + confirmación a partir de un {@code tokenMfaPendiente} ya
     * emitido por {@code /auth/login} o {@code /auth/seleccionar-tenant}, y devuelve el cuerpo
     * JSON final con {@code accessToken}/{@code refreshToken}/{@code empresaId} -- ver el
     * javadoc de la clase sobre por qué todo login exitoso en este árbol de pruebas pasa por
     * este paso.
     */
    private JsonNode completarMfaYObtenerTokens(String tokenMfaPendiente) throws Exception {
        String cuerpoEnrolamiento = mockMvc.perform(post("/auth/mfa/enrolar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secretoBase32 = objectMapper.readTree(cuerpoEnrolamiento).get("secretoBase32").asText();
        String codigo = totpService.generarCodigoActual(Base32Codec.decode(secretoBase32));

        String cuerpoConfirmacion = mockMvc.perform(post("/auth/mfa/confirmar")
                        .header("Authorization", "Bearer " + tokenMfaPendiente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MfaCodigoRequest(codigo))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpoConfirmacion);
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

    /** Segunda empresa + membresía ACTIVO para el mismo usuario, para forzar la rama de 2+ empresas del login. */
    private UUID crearSegundaEmpresaYMembresia(UUID usuarioId) {
        return TenantContextDescartable.ejecutar(() -> {
            Rol rolAdminEmpresa = rolRepository.findByCodigo(ROL_ADMIN_EMPRESA).orElseThrow();
            LocalDateTime ahora = LocalDateTime.now();

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Segunda Empresa S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuarioId);
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            UsuarioEmpresa membresia = new UsuarioEmpresa();
            membresia.setUsuarioId(usuarioId);
            membresia.setEmpresaId(empresa.getId());
            membresia.setRolId(rolAdminEmpresa.getId());
            membresia.setEstado(ESTADO_ACTIVO);
            membresia.setFechaIngreso(ahora);
            usuarioEmpresaRepository.save(membresia);

            return empresa.getId();
        });
    }

    private static String extraerToken(String cuerpoJson) {
        Matcher matcher = PATRON_TOKEN.matcher(cuerpoJson);
        assertThat(matcher.find()).as("El cuerpo capturado debe contener el enlace con el token: " + cuerpoJson).isTrue();
        return matcher.group(1);
    }

    @Test
    void loginConUnaEmpresaActivaAdminEmpresaEmiteTokenMfaPendienteQueSeCompletaHastaObtenerAccessToken()
            throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("login-unica", "clave-segura-123");

        LoginRequest login = new LoginRequest(datos.email(), datos.password());
        String cuerpoLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenMfaPendiente").exists())
                .andExpect(jsonPath("$.requiereEnrolamiento").value(true))
                .andReturn().getResponse().getContentAsString();
        String tokenMfaPendiente = objectMapper.readTree(cuerpoLogin).get("tokenMfaPendiente").asText();

        JsonNode tokens = completarMfaYObtenerTokens(tokenMfaPendiente);
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
        assertThat(tokens.get("empresaId").asText()).isEqualTo(datos.empresaId().toString());

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findById(datos.usuarioId()).orElseThrow();
            assertThat(usuario.isMfaHabilitado()).isTrue();
        });
    }

    @Test
    void loginConPasswordIncorrectoBloqueaAlQuintoFalloYRechazaElSextoAunConPasswordCorrecto() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("login-bloqueo", "clave-correcta-123");
        LoginRequest loginMalo = new LoginRequest(datos.email(), "clave-incorrecta");

        for (int intento = 1; intento <= 5; intento++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginMalo)))
                    .andExpect(status().isUnauthorized());
        }

        TenantContextDescartable.ejecutar((Runnable) () -> {
            Usuario usuario = usuarioRepository.findByEmail(datos.email()).orElseThrow();
            assertThat(usuario.getIntentosFallidos()).isEqualTo(5);
            assertThat(usuario.getBloqueadaHasta()).isNotNull().isAfter(LocalDateTime.now());
        });

        // 6to intento, ahora con la contraseña CORRECTA -- debe rechazarse igual por bloqueo,
        // con un mensaje distinto al de credenciales inválidas (sección 3.4).
        LoginRequest loginBueno = new LoginRequest(datos.email(), datos.password());
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBueno)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.mensaje").value(
                        "Tu cuenta está bloqueada temporalmente por múltiples intentos fallidos. Intenta de nuevo más tarde."));
    }

    @Test
    void loginParaCuentaPendienteVerificacionEsRechazadaConMensajeDistintoAlDeCredenciales() throws Exception {
        String email = emailUnico("login-pendiente");
        RegistroRequest registro = new RegistroRequest("Persona Pendiente", email, "clave-segura-123", "Empresa Pendiente S.A.");
        mockMvc.perform(post("/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());
        // Deliberadamente NO se verifica el correo.

        LoginRequest login = new LoginRequest(email, "clave-segura-123");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value(
                        "Tu cuenta aún no ha sido verificada. Revisa tu correo para activarla."));
    }

    @Test
    void tokenSeleccionTenantEsRechazadoComoBearerTokenEnRutaProtegidaMientrasQueUnAccessTokenNormalSiFunciona()
            throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("multi-empresa", "clave-segura-123");
        crearSegundaEmpresaYMembresia(datos.usuarioId());

        LoginRequest login = new LoginRequest(datos.email(), datos.password());
        String cuerpoLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenSeleccionTenant").exists())
                .andReturn().getResponse().getContentAsString();
        String tokenSeleccion = objectMapper.readTree(cuerpoLogin).get("tokenSeleccionTenant").asText();

        // Prueba end-to-end de la corrección de seguridad: un token de selección de tenant
        // NUNCA debe autenticar contra una ruta protegida genérica.
        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + tokenSeleccion))
                .andExpect(status().isUnauthorized());

        // La segunda empresa también es ADMIN_EMPRESA (ver crearSegundaEmpresaYMembresia):
        // seleccionar-tenant emite un token "MFA pendiente" en vez de un access token directo.
        SeleccionEmpresaRequest seleccion = new SeleccionEmpresaRequest(datos.empresaId());
        String cuerpoSeleccion = mockMvc.perform(post("/auth/seleccionar-tenant")
                        .header("Authorization", "Bearer " + tokenSeleccion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seleccion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenMfaPendiente").exists())
                .andReturn().getResponse().getContentAsString();
        String tokenMfaPendiente = objectMapper.readTree(cuerpoSeleccion).get("tokenMfaPendiente").asText();

        // El token MFA pendiente TAMPOCO debe autenticar una ruta protegida genérica --
        // ambos tokens de alcance mínimo quedan cubiertos por el mismo chequeo generalizado.
        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + tokenMfaPendiente))
                .andExpect(status().isUnauthorized());

        // Contraste positivo: un access token completo (emitido tras enrolar + confirmar MFA) SÍ autentica ahí.
        JsonNode tokens = completarMfaYObtenerTokens(tokenMfaPendiente);
        String accessToken = tokens.get("accessToken").asText();

        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void seleccionarTenantRechazaTokenAusenteYAccessTokenNormalUsadoEnSuLugar() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("seleccion-invalida", "clave-segura-123");

        SeleccionEmpresaRequest seleccion = new SeleccionEmpresaRequest(datos.empresaId());

        // Sin header Authorization en absoluto.
        mockMvc.perform(post("/auth/seleccionar-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seleccion)))
                .andExpect(status().isUnauthorized());

        // Un access token NORMAL (no de selección de tenant) presentado aquí también se rechaza.
        String accessTokenNormal = jwtService.generarToken(datos.usuarioId(), datos.empresaId());
        mockMvc.perform(post("/auth/seleccionar-tenant")
                        .header("Authorization", "Bearer " + accessTokenNormal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seleccion)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("El token de selección de tenant no es válido o ya expiró."));
    }

    @Test
    void cambiarTenantFuncionaDePuntaAPuntaYRechazaSinAutenticar() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("cambiar-tenant", "clave-segura-123");
        UUID segundaEmpresaId = crearSegundaEmpresaYMembresia(datos.usuarioId());

        // Sin autenticar -- rechazado.
        mockMvc.perform(post("/auth/cambiar-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeleccionEmpresaRequest(segundaEmpresaId))))
                .andExpect(status().isUnauthorized());

        // Con un access token vigente para la PRIMERA empresa, cambia a la segunda sin contraseña.
        String accessTokenPrimeraEmpresa = jwtService.generarToken(datos.usuarioId(), datos.empresaId());
        mockMvc.perform(post("/auth/cambiar-tenant")
                        .header("Authorization", "Bearer " + accessTokenPrimeraEmpresa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeleccionEmpresaRequest(segundaEmpresaId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.empresaId").value(segundaEmpresaId.toString()))
                // cambiar-tenant NO rota/emite refresh token (ver SesionService).
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void refrescarEmiteNuevoAccessTokenConRefreshTokenValidoYRechazaInvalidoExpiradoORevocado() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("refrescar", "clave-segura-123");

        LoginRequest login = new LoginRequest(datos.email(), datos.password());
        String cuerpoLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenMfaPendiente = objectMapper.readTree(cuerpoLogin).get("tokenMfaPendiente").asText();
        JsonNode tokensIniciales = completarMfaYObtenerTokens(tokenMfaPendiente);
        String refreshTokenCrudo = tokensIniciales.get("refreshToken").asText();

        // Refresh token válido -> nuevo access token para la misma empresa.
        RefrescarTokenRequest refrescar = new RefrescarTokenRequest(refreshTokenCrudo, datos.empresaId());
        mockMvc.perform(post("/auth/refrescar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refrescar)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.empresaId").value(datos.empresaId().toString()));

        // Refresh token que nunca existió -> inválido.
        mockMvc.perform(post("/auth/refrescar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefrescarTokenRequest("token-que-nunca-existio", datos.empresaId()))))
                .andExpect(status().isUnauthorized());

        // Expirado -> inválido.
        SesionRefreshToken sesion = TenantContextDescartable.ejecutar(() -> sesionRefreshTokenRepository.findAll()
                .stream()
                .filter(s -> s.getUsuarioId().equals(datos.usuarioId()))
                .findFirst().orElseThrow());
        TenantContextDescartable.ejecutar((Runnable) () -> {
            sesion.setExpiraEn(LocalDateTime.now().minusMinutes(1));
            sesionRefreshTokenRepository.save(sesion);
        });
        mockMvc.perform(post("/auth/refrescar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refrescar)))
                .andExpect(status().isUnauthorized());

        // Revocado -> inválido (se restaura la expiración para aislar la variable revocado).
        TenantContextDescartable.ejecutar((Runnable) () -> {
            sesion.setExpiraEn(LocalDateTime.now().plusDays(1));
            sesion.setRevocado(true);
            sesionRefreshTokenRepository.save(sesion);
        });
        mockMvc.perform(post("/auth/refrescar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refrescar)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refrescarRechazaEmpresaSinMembresiaActiva() throws Exception {
        RegistroYVerificacion datos = registrarYVerificarUsuario("refrescar-membresia", "clave-segura-123");

        LoginRequest login = new LoginRequest(datos.email(), datos.password());
        String cuerpoLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenMfaPendiente = objectMapper.readTree(cuerpoLogin).get("tokenMfaPendiente").asText();
        JsonNode tokensIniciales = completarMfaYObtenerTokens(tokenMfaPendiente);
        String refreshTokenCrudo = tokensIniciales.get("refreshToken").asText();

        RefrescarTokenRequest refrescarEmpresaAjena = new RefrescarTokenRequest(refreshTokenCrudo, UUID.randomUUID());
        mockMvc.perform(post("/auth/refrescar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refrescarEmpresaAjena)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("No tienes acceso activo a la empresa solicitada."));
    }
}
