package cr.ac.fractall.seguridad.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.CambiarRolRequest;
import cr.ac.fractall.seguridad.dto.InvitarUsuarioRequest;
import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.InvitacionUsuarioRepository;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.InvitacionUsuarioService;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.seguridad.servicio.TokenHasher;
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
    private static final String ESTADO_ACEPTADA = "ACEPTADA";
    private static final String ESTADO_REVOCADA = "REVOCADA";
    private static final String ESTADO_EXPIRADA = "EXPIRADA";
    private static final String ESTADO_INVITACION_PENDIENTE = "INVITACION_PENDIENTE";
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
    private InvitacionUsuarioService invitacionUsuarioService;

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

    @Test
    void emitirConRolCodigoInexistenteEsRechazadaConBadRequestSinCrearInvitacion() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-inexistente", ROL_ADMIN_EMPRESA);
        UUID empresaId = semilla.empresaId();
        String tokenActor = tokenPara(semilla.actorId(), empresaId);
        String email = emailUnico("invitado-rol-invalido");

        mockMvc.perform(post("/usuarios/invitar")
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitarUsuarioRequest(email, "ROL_QUE_NO_EXISTE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        Optional<InvitacionUsuario> invitacion = TenantContextDescartable.ejecutar(
                () -> invitacionUsuarioRepository.findByEmpresaIdAndEmailAndEstado(empresaId, email, ESTADO_PENDIENTE));
        assertThat(invitacion).as("un rolCodigo inexistente nunca debe crear la fila de invitación").isEmpty();
    }

    /**
     * Emite una invitación llamando directamente a {@code InvitacionUsuarioService.emitir}
     * (no vía HTTP): el controlador nunca expone el token crudo en su respuesta (anti-
     * enumeración), así que la única forma de obtenerlo para sembrar el escenario de
     * "aceptar" es invocar el servicio en el propio hilo de prueba, envuelto en
     * {@code TenantContextDescartable} por el mismo motivo que {@code emitir()} lo necesita
     * cuando corre fuera de {@code JwtTenantFilter}.
     */
    private String emitirTokenCrudo(UUID actorId, UUID empresaId, String email, String rolCodigo) {
        return TenantContextDescartable.ejecutar(() -> invitacionUsuarioService
                .emitir(actorId, empresaId, email, rolCodigo)
                .orElseThrow(() -> new IllegalStateException("emitir() no debió omitir un correo nuevo"))
                .tokenCrudo());
    }

    /**
     * Acuña el access token del invitado que autentica {@code POST
     * /usuarios/invitacion/{token}/aceptar}: el filtro solo necesita un claim
     * {@code empresaId} válido para poblar {@code TenantContext} (JwtTenantFilter), y este
     * endpoint no exige ninguna membresía sobre esa empresa (design.md: "ninguno: el token ES
     * la autorización") -- por eso un UUID descartable es suficiente, igual que el tenant
     * "actual" del invitado es por definición distinto al de la empresa que invita.
     */
    private String tokenInvitado(UUID invitadoId) {
        return jwtService.generarToken(invitadoId, UUID.randomUUID());
    }

    private void forzarEstadoInvitacion(String tokenCrudo, String estado) {
        TenantContextDescartable.ejecutar((Runnable) () -> {
            InvitacionUsuario invitacion = invitacionUsuarioRepository
                    .findByTokenHash(TokenHasher.sha256Hex(tokenCrudo)).orElseThrow();
            invitacion.setEstado(estado);
            invitacionUsuarioRepository.save(invitacion);
        });
    }

    private String estadoInvitacionPersistido(String tokenCrudo) {
        return TenantContextDescartable.ejecutar(() -> invitacionUsuarioRepository
                .findByTokenHash(TokenHasher.sha256Hex(tokenCrudo)).orElseThrow().getEstado());
    }

    @Test
    void aceptarConTokenInexistenteEsRechazadaConBadRequest() throws Exception {
        UUID invitadoId = crearUsuarioConEmailExacto(emailUnico("token-inexistente"));

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", "token-que-no-existe")
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_INVITACION_INVALIDA));
    }

    @Test
    void aceptarConTokenExpiradoEsRechazadaYQuedaMarcadaExpirada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-expira", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("expirado");
        UUID invitadoId = crearUsuarioConEmailExacto(emailInvitado);
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);

        TenantContextDescartable.ejecutar((Runnable) () -> {
            InvitacionUsuario invitacion = invitacionUsuarioRepository
                    .findByTokenHash(TokenHasher.sha256Hex(tokenCrudo)).orElseThrow();
            invitacion.setExpiraEn(LocalDateTime.now().minusDays(1));
            invitacionUsuarioRepository.save(invitacion);
        });

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", tokenCrudo)
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_INVITACION_INVALIDA));

        assertThat(estadoInvitacionPersistido(tokenCrudo))
                .as("el intento fallido sobre un token vencido debe dejar la fila EXPIRADA")
                .isEqualTo(ESTADO_EXPIRADA);
    }

    @Test
    void aceptarConTokenYaAceptadoEsRechazada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-ya-aceptada", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("ya-aceptada");
        UUID invitadoId = crearUsuarioConEmailExacto(emailInvitado);
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);
        forzarEstadoInvitacion(tokenCrudo, ESTADO_ACEPTADA);

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", tokenCrudo)
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_INVITACION_INVALIDA));
    }

    @Test
    void aceptarConTokenRevocadoEsRechazada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-revocada", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("revocada");
        UUID invitadoId = crearUsuarioConEmailExacto(emailInvitado);
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);
        forzarEstadoInvitacion(tokenCrudo, ESTADO_REVOCADA);

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", tokenCrudo)
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_INVITACION_INVALIDA));
    }

    @Test
    void aceptarConTokenValidoActivaMembresiaYMarcaInvitacionAceptada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-aceptar", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("acepta-consulta");
        UUID invitadoId = crearUsuarioConEmailExacto(emailInvitado);
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_CONSULTA);

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", tokenCrudo)
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        UsuarioEmpresa membresia = TenantContextDescartable.ejecutar(() -> usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(invitadoId, semilla.empresaId(), ESTADO_ACTIVO)
                .orElseThrow());
        Rol rolConsulta = TenantContextDescartable.ejecutar(() -> rolRepository.findByCodigo(ROL_CONSULTA).orElseThrow());
        assertThat(membresia.getRolId()).isEqualTo(rolConsulta.getId());

        assertThat(estadoInvitacionPersistido(tokenCrudo)).isEqualTo(ESTADO_ACEPTADA);
    }

    @Test
    void aceptarConRolAdminEmpresaMarcaMfaRequeridoYRespondeConMfaPendiente() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("admin-invita-admin", ROL_ADMIN_EMPRESA);
        String emailInvitado = emailUnico("acepta-admin");
        UUID invitadoId = crearUsuarioConEmailExacto(emailInvitado);
        String tokenCrudo = emitirTokenCrudo(semilla.actorId(), semilla.empresaId(), emailInvitado, ROL_ADMIN_EMPRESA);

        mockMvc.perform(post("/usuarios/invitacion/{token}/aceptar", tokenCrudo)
                        .header("Authorization", "Bearer " + tokenInvitado(invitadoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenMfaPendiente").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        boolean mfaRequerido = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findById(invitadoId).orElseThrow().isMfaRequerido());
        assertThat(mfaRequerido).as("aceptar como ADMIN_EMPRESA debe dejar mfaRequerido persistido").isTrue();

        UsuarioEmpresa membresia = TenantContextDescartable.ejecutar(() -> usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(invitadoId, semilla.empresaId(), ESTADO_ACTIVO)
                .orElseThrow());
        assertThat(membresia).as("la membresía debe activarse aunque la respuesta sea MFA pendiente").isNotNull();
    }

    private record MiembroSemilla(UUID usuarioId, String email) {
    }

    /**
     * Siembra un miembro adicional (no el actor dueño de {@code crearEmpresaConActor}) de una
     * empresa ya existente, con el estado indicado -- usado para poblar {@code GET /usuarios}
     * con filas {@code ACTIVO} e {@code INVITACION_PENDIENTE} sin pasar por el flujo completo
     * de invitar+aceptar (fuera del alcance de PR5a, cubierto por PR3a/PR3b).
     */
    private MiembroSemilla agregarMiembro(UUID empresaId, String rolCodigo, String estado) {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();
            Rol rol = rolRepository.findByCodigo(rolCodigo).orElseThrow();
            String email = emailUnico("miembro");

            Usuario usuario = new Usuario();
            usuario.setNombre("Miembro " + email);
            usuario.setEmail(email);
            usuario.setPasswordHash("hash-no-relevante");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setMfaRequerido(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            UsuarioEmpresa membresia = new UsuarioEmpresa();
            membresia.setUsuarioId(usuario.getId());
            membresia.setEmpresaId(empresaId);
            membresia.setRolId(rol.getId());
            membresia.setEstado(estado);
            membresia.setFechaIngreso(ahora);
            usuarioEmpresaRepository.save(membresia);

            return new MiembroSemilla(usuario.getId(), usuario.getEmail());
        });
    }

    @Test
    void listarSinPermisoUsuarioVerEsRechazada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("listar-sin-permiso", ROL_CONSULTA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());

        mockMvc.perform(get("/usuarios")
                        .header("Authorization", "Bearer " + tokenActor))
                .andExpect(status().isForbidden());
    }

    @Test
    void listarIncluyeMiembrosActivosYPendientes() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("listar-admin", ROL_ADMIN_EMPRESA);
        UUID empresaId = semilla.empresaId();
        String tokenActor = tokenPara(semilla.actorId(), empresaId);

        MiembroSemilla activo = agregarMiembro(empresaId, ROL_CONSULTA, ESTADO_ACTIVO);
        MiembroSemilla pendiente = agregarMiembro(empresaId, ROL_CONSULTA, ESTADO_INVITACION_PENDIENTE);

        String cuerpo = mockMvc.perform(get("/usuarios")
                        .header("Authorization", "Bearer " + tokenActor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> miembros = objectMapper.readValue(cuerpo, new TypeReference<>() {
        });

        Map<String, Object> filaActivo = miembros.stream()
                .filter(m -> activo.usuarioId().toString().equals(m.get("usuarioId")))
                .findFirst().orElseThrow(() -> new AssertionError("miembro ACTIVO ausente del listado"));
        assertThat(filaActivo.get("estado")).isEqualTo(ESTADO_ACTIVO);

        Map<String, Object> filaPendiente = miembros.stream()
                .filter(m -> pendiente.usuarioId().toString().equals(m.get("usuarioId")))
                .findFirst().orElseThrow(() -> new AssertionError("miembro INVITACION_PENDIENTE ausente del listado"));
        assertThat(filaPendiente.get("estado")).isEqualTo(ESTADO_INVITACION_PENDIENTE);
    }

    @Test
    void listarNoFiltraMiembrosDeOtraEmpresa() throws Exception {
        EmpresaConActor empresaA = crearEmpresaConActor("listar-aislamiento-a", ROL_ADMIN_EMPRESA);
        EmpresaConActor empresaB = crearEmpresaConActor("listar-aislamiento-b", ROL_ADMIN_EMPRESA);

        MiembroSemilla exclusivoDeA = agregarMiembro(empresaA.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);

        String tokenActorB = tokenPara(empresaB.actorId(), empresaB.empresaId());

        String cuerpo = mockMvc.perform(get("/usuarios")
                        .header("Authorization", "Bearer " + tokenActorB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo)
                .as("un caller de la empresa B nunca debe ver un miembro exclusivo de la empresa A")
                .doesNotContain(exclusivoDeA.email());
    }

    @Test
    void cambiarRolSinPermisoUsuarioEditarRolEsRechazada() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-sin-permiso", ROL_CONSULTA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());
        MiembroSemilla objetivo = agregarMiembro(semilla.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", objetivo.usuarioId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_ADMIN_EMPRESA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cambiarRolDeMiembroDeOtraEmpresaEsRechazadoCon404() throws Exception {
        EmpresaConActor empresaA = crearEmpresaConActor("rol-aislamiento-a", ROL_ADMIN_EMPRESA);
        EmpresaConActor empresaB = crearEmpresaConActor("rol-aislamiento-b", ROL_ADMIN_EMPRESA);
        MiembroSemilla exclusivoDeB = agregarMiembro(empresaB.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);
        String tokenActorA = tokenPara(empresaA.actorId(), empresaA.empresaId());

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", exclusivoDeB.usuarioId())
                        .header("Authorization", "Bearer " + tokenActorA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_ADMIN_EMPRESA))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cambiarRolConRolCodigoInexistenteEsRechazadoConBadRequest() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-invalido", ROL_ADMIN_EMPRESA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());
        MiembroSemilla objetivo = agregarMiembro(semilla.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", objetivo.usuarioId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest("ROL_QUE_NO_EXISTE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cambiarRolAutodegradacionEsRechazadaConConflictoYRolSinCambios() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-autogestion", ROL_ADMIN_EMPRESA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", semilla.actorId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_CONSULTA))))
                .andExpect(status().isConflict());

        UsuarioEmpresa membresiaActor = TenantContextDescartable.ejecutar(() -> usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(semilla.actorId(), semilla.empresaId(), ESTADO_ACTIVO)
                .orElseThrow());
        Rol rolAdmin = TenantContextDescartable.ejecutar(() -> rolRepository.findByCodigo(ROL_ADMIN_EMPRESA).orElseThrow());
        assertThat(membresiaActor.getRolId())
                .as("un 409 de autogestión nunca debe mutar el rol del propio actor")
                .isEqualTo(rolAdmin.getId());
    }

    @Test
    void cambiarRolExitosoActualizaRolYPersisteNuevoRol() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-exitoso", ROL_ADMIN_EMPRESA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());
        MiembroSemilla objetivo = agregarMiembro(semilla.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", objetivo.usuarioId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_ADMIN_EMPRESA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolCodigo").value(ROL_ADMIN_EMPRESA));

        UsuarioEmpresa membresia = TenantContextDescartable.ejecutar(() -> usuarioEmpresaRepository
                .findByUsuarioIdAndEmpresaIdAndEstado(objetivo.usuarioId(), semilla.empresaId(), ESTADO_ACTIVO)
                .orElseThrow());
        Rol rolAdmin = TenantContextDescartable.ejecutar(() -> rolRepository.findByCodigo(ROL_ADMIN_EMPRESA).orElseThrow());
        assertThat(membresia.getRolId()).isEqualTo(rolAdmin.getId());
    }

    @Test
    void cambiarRolAAdminEmpresaMarcaMfaRequeridoSinTokenMfa() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-promocion-mfa", ROL_ADMIN_EMPRESA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());
        MiembroSemilla objetivo = agregarMiembro(semilla.empresaId(), ROL_CONSULTA, ESTADO_ACTIVO);

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", objetivo.usuarioId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_ADMIN_EMPRESA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.tokenMfaPendiente").doesNotExist());

        boolean mfaRequerido = TenantContextDescartable.ejecutar(
                () -> usuarioRepository.findById(objetivo.usuarioId()).orElseThrow().isMfaRequerido());
        assertThat(mfaRequerido)
                .as("promover a ADMIN_EMPRESA vía PATCH .../rol debe dejar mfaRequerido persistido, sin token")
                .isTrue();
    }

    @Test
    void cambiarRolConDosAdministradoresActivosPermiteDegradarAlPenultimo() throws Exception {
        EmpresaConActor semilla = crearEmpresaConActor("rol-penultimo", ROL_ADMIN_EMPRESA);
        String tokenActor = tokenPara(semilla.actorId(), semilla.empresaId());
        MiembroSemilla otroAdmin = agregarMiembro(semilla.empresaId(), ROL_ADMIN_EMPRESA, ESTADO_ACTIVO);

        mockMvc.perform(patch("/usuarios/{usuarioId}/rol", otroAdmin.usuarioId())
                        .header("Authorization", "Bearer " + tokenActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CambiarRolRequest(ROL_CONSULTA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolCodigo").value(ROL_CONSULTA));
    }
}
