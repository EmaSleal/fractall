package cr.ac.fractall.seguridad.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
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

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.LoginRequest;
import cr.ac.fractall.seguridad.dto.RegistroPorInvitacionRequest;
import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.InvitacionUsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioTokenRepository;
import cr.ac.fractall.seguridad.servicio.InvitacionUsuarioService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de {@code POST /auth/registro/invitacion} (Fase B, PR4 -- ver
 * design.md, sección "RegistroService.registrarPorInvitacion"). Cubre al invitado que NO
 * tiene cuenta todavía -- el caso de un invitado que YA tiene cuenta lo cubre
 * {@code UsuarioFlujoInvitacionTest#aceptar*} vía {@code POST /usuarios/invitacion/{token}/aceptar}.
 *
 * <p>Mismo bootstrap de Postgres+Vault+stub de Resend que {@code AuthFlujoRegistroYVerificacionTest}
 * y {@code UsuarioFlujoInvitacionTest} -- {@code VaultConfig} conecta de forma ansiosa al
 * arrancar el contexto, sin importar si este archivo dispara o no un envío real de correo.
 * Ningún test de este archivo dispara un envío de Resend: la invitación en sí se emite
 * llamando directamente a {@code InvitacionUsuarioService.emitir} desde el hilo de prueba
 * (mismo estilo que {@code UsuarioFlujoInvitacionTest#emitirTokenCrudo}), nunca vía
 * {@code POST /usuarios/invitar}.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RegistroPorInvitacionTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";
    private static final String ROL_CONSULTA = "CONSULTA";
    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final String ESTADO_ACEPTADA = "ACEPTADA";
    private static final String TIPO_VERIFICACION_EMAIL = "VERIFICACION_EMAIL";
    private static final String MENSAJE_INVITACION_INVALIDA =
            "La invitación no es válida, ya fue utilizada o expiró.";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;
    private static HttpServer resendStub;

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
            exchange.getRequestBody().readAllBytes();
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
    private InvitacionUsuarioRepository invitacionUsuarioRepository;

    @Autowired
    private InvitacionUsuarioService invitacionUsuarioService;

    @Autowired
    private UsuarioTokenRepository usuarioTokenRepository;

    private static String emailUnico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID() + "@fractall.test";
    }

    private record EmpresaConActor(UUID empresaId, UUID actorId) {
    }

    /** Mismo estilo que {@code UsuarioFlujoInvitacionTest#crearEmpresaConActor}. */
    private EmpresaConActor crearEmpresaConActor(String prefijo, String rolCodigoActor) {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();
            Rol rol = rolRepository.findByCodigo(rolCodigoActor).orElseThrow();

            Usuario actor = new Usuario();
            actor.setNombre("Persona " + prefijo);
            actor.setEmail(emailUnico(prefijo));
            actor.setPasswordHash("hash-no-relevante");
            actor.setEmailVerificado(true);
            actor.setEstado("ACTIVA");
            actor.setMfaHabilitado(false);
            actor.setMfaRequerido(false);
            actor.setIntentosFallidos(0);
            actor.setCreateDate(ahora);
            actor.setUpdateDate(ahora);
            actor = usuarioRepository.save(actor);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa " + prefijo + " S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(actor.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            UsuarioEmpresa membresia = new UsuarioEmpresa();
            membresia.setUsuarioId(actor.getId());
            membresia.setEmpresaId(empresa.getId());
            membresia.setRolId(rol.getId());
            membresia.setEstado(ESTADO_ACTIVO);
            membresia.setFechaIngreso(ahora);
            usuarioEmpresaRepository.save(membresia);

            return new EmpresaConActor(empresa.getId(), actor.getId());
        });
    }

    /**
     * Mismo estilo que {@code UsuarioFlujoInvitacionTest#emitirTokenCrudo}: se invoca
     * {@code InvitacionUsuarioService.emitir} directamente (no vía HTTP) para obtener el token
     * crudo, que nunca se expone en una respuesta HTTP real.
     */
    private String emitirTokenCrudo(UUID actorId, UUID empresaId, String email, String rolCodigo) {
        return TenantContextDescartable.ejecutar(() -> invitacionUsuarioService
                .emitir(actorId, empresaId, email, rolCodigo)
                .orElseThrow(() -> new IllegalStateException("emitir() no debió omitir un correo nuevo"))
                .tokenCrudo());
    }

    private String estadoInvitacionPersistido(String tokenCrudo) {
        return TenantContextDescartable.ejecutar(() -> invitacionUsuarioRepository
                .findByTokenHash(cr.ac.fractall.seguridad.servicio.TokenHasher.sha256Hex(tokenCrudo))
                .orElseThrow().getEstado());
    }

    @Test
    void registrarPorInvitacionCreaUsuarioActivoConRolDeLaInvitacionYMarcaAceptada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-invita-nuevo", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("nuevo-invitado");
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);

        RegistroPorInvitacionRequest request =
                new RegistroPorInvitacionRequest(tokenCrudo, "Persona Nueva", "clave-segura-123", false);

        mockMvc.perform(post("/auth/registro/invitacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        Usuario usuarioCreado = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findByEmail(emailInvitado).orElseThrow());
        assertThat(usuarioCreado.getNombre()).isEqualTo("Persona Nueva");
        assertThat(usuarioCreado.getEstado()).isEqualTo("ACTIVA");
        assertThat(usuarioCreado.isEmailVerificado()).isTrue();

        UsuarioEmpresa membresia = TenantContextDescartable.ejecutar(() -> usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(usuarioCreado.getId(), semilla.empresaId(), ESTADO_ACTIVO)
                .orElseThrow());
        Rol rolConsulta = TenantContextDescartable.ejecutar(() -> rolRepository.findByCodigo(ROL_CONSULTA).orElseThrow());
        assertThat(membresia.getRolId()).isEqualTo(rolConsulta.getId());

        assertThat(estadoInvitacionPersistido(tokenCrudo)).isEqualTo(ESTADO_ACEPTADA);
    }

    @Test
    void registrarPorInvitacionIgnoraElEmailDelCuerpoYUsaSiempreElDeLaInvitacion() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-ignora-email", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("email-real");
        String emailAjeno = emailUnico("email-ajeno-en-el-body");
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);

        // El DTO real no tiene campo "email" -- se construye el JSON a mano para probar que un
        // intento de colar un correo distinto en el cuerpo es ignorado (deserialización
        // silenciosa de una propiedad desconocida), no aceptado.
        String cuerpoConEmailAjeno = """
                {"invitacionToken": "%s", "nombre": "Persona Con Email Ajeno", "password": "clave-segura-123", \
                "activarMfa": false, "email": "%s"}
                """.formatted(tokenCrudo, emailAjeno);

        mockMvc.perform(post("/auth/registro/invitacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoConEmailAjeno))
                .andExpect(status().isOk());

        assertThat(TenantContextDescartable.ejecutar(() -> usuarioRepository.findByEmail(emailAjeno)))
                .as("el correo del cuerpo nunca debe usarse").isEmpty();

        Usuario usuarioCreado = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findByEmail(emailInvitado).orElseThrow());
        assertThat(usuarioCreado.getEmail()).isEqualTo(emailInvitado);
    }

    @Test
    void registrarPorInvitacionNoEmiteTokenDeVerificacionDeEmailYLaCuentaEsUtilizableDeInmediato() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-sin-verificacion", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("sin-verificacion");
        String password = "clave-segura-123";
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);

        RegistroPorInvitacionRequest request =
                new RegistroPorInvitacionRequest(tokenCrudo, "Persona Sin Verificacion", password, false);

        mockMvc.perform(post("/auth/registro/invitacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        UUID usuarioId = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findByEmail(emailInvitado).orElseThrow().getId());

        Optional<?> tokenVerificacion = TenantContextDescartable.ejecutar(
                () -> usuarioTokenRepository.findFirstByUsuarioIdAndTipoOrderByCreateDateDesc(
                        usuarioId, TIPO_VERIFICACION_EMAIL));
        assertThat(tokenVerificacion).as("registrarPorInvitacion nunca debe emitir VERIFICACION_EMAIL").isEmpty();

        // La cuenta debe ser utilizable de inmediato: un login con las mismas credenciales NO
        // debe rechazarse con CuentaNoVerificadaException (403).
        LoginRequest login = new LoginRequest(emailInvitado, password);
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void registrarPorInvitacionConTokenInvalidoEsRechazadaConBadRequestSinCrearUsuario() throws Exception {
        RegistroPorInvitacionRequest request =
                new RegistroPorInvitacionRequest("token-que-no-existe", "Persona Fantasma", "clave-segura-123", false);

        mockMvc.perform(post("/auth/registro/invitacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_INVITACION_INVALIDA));
    }

    @Test
    void registrarPorInvitacionConRolAdminEmpresaMarcaMfaRequeridoYRespondeConMfaPendiente() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-invita-admin-nuevo", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("nuevo-admin");
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_ADMIN_EMPRESA);

        RegistroPorInvitacionRequest request =
                new RegistroPorInvitacionRequest(tokenCrudo, "Persona Nueva Admin", "clave-segura-123", false);

        mockMvc.perform(post("/auth/registro/invitacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenMfaPendiente").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        boolean mfaRequerido = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findByEmail(emailInvitado).orElseThrow().isMfaRequerido());
        assertThat(mfaRequerido)
                .as("registrar por invitación como ADMIN_EMPRESA debe dejar mfaRequerido=true aunque se pida false")
                .isTrue();
    }
}
