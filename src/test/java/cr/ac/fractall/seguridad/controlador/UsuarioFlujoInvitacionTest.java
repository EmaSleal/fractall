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
import java.util.List;
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
import cr.ac.fractall.seguridad.dto.InvitarUsuarioRequest;
import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.InvitacionUsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de {@code POST /usuarios/invitar} (Fase B, PR3a -- ver design.md,
 * sección "InvitacionUsuarioService" y su "Data Flow"). No pasa por {@code /auth/registro}
 * ni por login/MFA: los actores y las membresías se siembran directamente por repositorio
 * (mismo estilo que {@code AislamientoMultiTenantTest}), y el access token se acuña
 * directamente con {@code jwtService.generarToken(usuarioId, empresaId)} (mismo estilo que
 * {@code AuthFlujoLoginYTenantTest#seleccionarTenantRechazaTokenAusenteYAccessTokenNormalUsadoEnSuLugar}).
 *
 * <p>Mismo stub embebido de Resend que {@code AuthFlujoLoginYTenantTest} -- evita una llamada
 * de red real a la API de Resend durante el test; no se afirma nada sobre su contenido aquí,
 * solo se evita que {@code ResendEmailClient} intente golpear la red real.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class UsuarioFlujoInvitacionTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";
    private static final String ROL_CONSULTA = "CONSULTA";
    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final String ESTADO_PENDIENTE = "PENDIENTE";
    private static final String ESTADO_INVITACION_PENDIENTE = "INVITACION_PENDIENTE";

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
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @Autowired
    private RolRepository rolRepository;

    @Autowired
    private InvitacionUsuarioRepository invitacionUsuarioRepository;

    @Autowired
    private JwtService jwtService;

    private static String emailUnico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID() + "@fractall.test";
    }

    private record EmpresaConActor(UUID empresaId, UUID actorId) {
    }

    /**
     * Crea un usuario ACTIVA (el actor), una empresa cuyo {@code creado_por} es ese mismo
     * usuario (FK {@code empresa_creado_por_fkey} obliga a que exista primero), y una
     * membresía ACTIVO de ese usuario en esa empresa con el rol indicado -- mismo orden de
     * creación que {@code RegistroService#registrar}.
     */
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

    private String tokenPara(UUID usuarioId, UUID empresaId) {
        return jwtService.generarToken(usuarioId, empresaId);
    }

    @Test
    void emitirSinPermisoUsuarioInvitarEsRechazadaSinCrearInvitacion() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("sin-permiso", ROL_CONSULTA);
        UUID empresaId = semilla.empresaId();
        UUID actorId = semilla.actorId();
        String tokenActor = tokenPara(actorId, empresaId);
        String email = emailUnico("invitado-rechazado");

        mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(email, ROL_CONSULTA))))
                .andExpect(status().isForbidden());

        Optional<InvitacionUsuario> invitacion = TenantContextDescartable.ejecutar(
                () -> invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, email, ESTADO_PENDIENTE));
        assertThat(invitacion).as("un 403 nunca debe crear la fila de invitación").isEmpty();
    }

    @Test
    void emitirRespondeIdenticoParaCorreoConCuentaYSinCuenta() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-uniforme", ROL_ADMIN_EMPRESA);
        UUID empresaId = semilla.empresaId();
        String tokenActor = tokenPara(semilla.actorId(), empresaId);

        String emailSinCuenta = emailUnico("sin-cuenta");
        String emailConCuenta = emailUnico("con-cuenta");
        UUID titularId = crearUsuarioConEmailExacto(emailConCuenta);

        String cuerpoSinCuenta = mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(emailSinCuenta, ROL_CONSULTA))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String cuerpoConCuenta = mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(emailConCuenta, ROL_CONSULTA))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpoSinCuenta).as("la respuesta debe ser idéntica exista o no la cuenta").isEqualTo(cuerpoConCuenta);

        TenantContextDescartable.ejecutar((Runnable) () -> {
            assertThat(invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, emailSinCuenta, ESTADO_PENDIENTE))
                    .isPresent();
            assertThat(invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, emailConCuenta, ESTADO_PENDIENTE))
                    .isPresent();
        });

        assertThat(titularId).isNotNull();
    }

    private UUID crearUsuarioConEmailExacto(String email) {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();
            Usuario usuario = new Usuario();
            usuario.setNombre("Titular Existente");
            usuario.setEmail(email);
            usuario.setPasswordHash("hash-no-relevante");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setMfaRequerido(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            return usuarioRepository.save(usuario).getId();
        });
    }

    @Test
    void emitirSegundaInvitacionVivaAlMismoCorreoEsOmitidaSilenciosamente() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-duplicado", ROL_ADMIN_EMPRESA);
        UUID empresaId = semilla.empresaId();
        String tokenActor = tokenPara(semilla.actorId(), empresaId);
        String email = emailUnico("duplicado");

        mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(email, ROL_CONSULTA))))
                .andExpect(status().isAccepted());

        String tokenHashPrimeraInvitacion = TenantContextDescartable.ejecutar(() ->
                invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, email, ESTADO_PENDIENTE)
                        .orElseThrow().getTokenHash());

        // Segunda invitación al MISMO correo+empresa mientras la primera sigue PENDIENTE.
        mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(email, ROL_CONSULTA))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mensaje").value(
                        "Si el correo es válido, se enviará una invitación en unos minutos."));

        List<InvitacionUsuario> todasLasInvitaciones = TenantContextDescartable.ejecutar(
                () -> invitacionUsuarioRepository.findAll().stream()
                        .filter(i -> i.getEmpresaId().equals(empresaId) && i.getEmail().equals(email))
                        .toList());
        assertThat(todasLasInvitaciones).as("la segunda invitación viva no debe crear una segunda fila").hasSize(1);
        assertThat(todasLasInvitaciones.get(0).getTokenHash())
                .as("el token de la invitación original no debe reemplazarse")
                .isEqualTo(tokenHashPrimeraInvitacion);
    }

    @Test
    void emitirParaCorreoConCuentaExistenteCreaMembresiaPendiente() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-membresia", ROL_ADMIN_EMPRESA);
        UUID empresaId = semilla.empresaId();
        String tokenActor = tokenPara(semilla.actorId(), empresaId);

        String emailInvitado = emailUnico("ya-tiene-cuenta");
        UUID usuarioInvitadoId = crearUsuarioConEmailExacto(emailInvitado);

        mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(emailInvitado, ROL_CONSULTA))))
                .andExpect(status().isAccepted());

        UsuarioEmpresa membresia = TenantContextDescartable.ejecutar(() ->
                usuarioEmpresaRepository
                        .findByUsuarioIdAndEmpresaIdAndEstado(usuarioInvitadoId, empresaId, ESTADO_INVITACION_PENDIENTE)
                        .orElseThrow());

        Rol rolConsulta = TenantContextDescartable.ejecutar(() -> rolRepository.findByCodigo(ROL_CONSULTA).orElseThrow());
        assertThat(membresia.getRolId()).isEqualTo(rolConsulta.getId());
    }
}
