package cr.ac.fractall.catalogo.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import tools.jackson.databind.ObjectMapper;

import cr.ac.fractall.catalogo.dto.CrearClienteExoneracionRequest;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.catalogo.dto.CrearProductoRequest;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.dto.CabysDTO;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración a nivel HTTP de los 3 controladores de la Fase 6 (sección 4.10, 4.11 y
 * 4.15 de {@code arquitectura-facturacion-electronica-cr.md}) -- {@code ProductoController},
 * {@code ClienteController} y {@code ClienteExoneracionController} -- consolidados en una sola
 * clase para no triplicar el bootstrap de Postgres + Vault vía Testcontainers (obligatorio para
 * CUALQUIER {@code @SpringBootTest} desde la Fase 3, ver el javadoc de
 * {@code EmpresaControllerTest}), aunque ninguno de estos 3 controladores toque Vault.
 *
 * <p>{@link HaciendaApiService} se reemplaza con {@code @MockitoBean} -- evita una llamada HTTP
 * real a {@code api.hacienda.go.cr} en esta prueba de integración, mientras se ejercita el resto
 * de la pila real (controlador -&gt; servicio -&gt; repositorio -&gt; Postgres real vía Flyway
 * {@code V8__cliente_exoneracion.sql}).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CatalogoControllerTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

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

    // Jackson 3 (tools.jackson.databind), NO com.fasterxml.jackson.databind: este proyecto trae
    // ambas mayores en el classpath de test -- Jackson 3 vía spring-boot-starter-jackson(-test),
    // que es la que Spring realmente usa para (de)serializar HTTP, y Jackson 2 transitivamente
    // vía jjwt-jackson. com.fasterxml.jackson.databind.ObjectMapper (Jackson 2) no soporta
    // java.time.LocalDateTime sin el módulo jackson-datatype-jsr310, ausente aquí -- Jackson 3
    // sí lo soporta de forma nativa, sin módulo aparte.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private HaciendaApiService haciendaApiService;

    /** Crea usuario + empresa y devuelve un access token normal para esa empresa. */
    private String crearUsuarioEmpresaYToken() {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();

            Usuario usuario = new Usuario();
            usuario.setNombre("Persona de prueba CatalogoController");
            usuario.setEmail("catalogo-controller-" + UUID.randomUUID() + "@fractall.test");
            usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa de Prueba CatalogoController S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuario.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            return jwtService.generarToken(usuario.getId(), empresa.getId());
        });
    }

    private void simularCabysValido(String codigo, String descripcion, int impuesto) {
        when(haciendaApiService.buscarCabys(anyString(), anyInt())).thenReturn(
                CabysBusquedaDTO.builder()
                        .exitosa(true)
                        .total(1)
                        .cantidad(1)
                        .cabys(List.of(CabysDTO.builder()
                                .codigo(codigo)
                                .descripcion(descripcion)
                                .impuesto(impuesto)
                                .build()))
                        .build());
    }

    @Test
    void postProductoConCabysValidoRetorna201ConCamposDerivados() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        simularCabysValido("2132100000100", "Jugo de tomate concentrado", 13);

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-HTTP-001", "Jugo de tomate", "2132100000100", null, new java.math.BigDecimal("1500"), null);

        mockMvc.perform(post("/catalogo/productos")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descripcionCabys").value("Jugo de tomate concentrado"))
                .andExpect(jsonPath("$.porcentajeImpuesto").value(13))
                .andExpect(jsonPath("$.gravado").value(true));
    }

    @Test
    void postProductoConCabysSinCoincidenciaRetorna400() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        when(haciendaApiService.buscarCabys(anyString(), anyInt())).thenReturn(
                CabysBusquedaDTO.builder().exitosa(true).total(0).cantidad(0).cabys(List.of()).build());

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-HTTP-002", "Producto inexistente", "9999999999999", null, java.math.BigDecimal.TEN, null);

        mockMvc.perform(post("/catalogo/productos")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postProductoCuandoLaLlamadaAHaciendaFallaRetorna503() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        when(haciendaApiService.buscarCabys(anyString(), anyInt())).thenReturn(
                CabysBusquedaDTO.builder().exitosa(false).mensajeError("Timeout").build());

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-HTTP-003", "Jugo de tomate", "2132100000100", null, java.math.BigDecimal.TEN, null);

        mockMvc.perform(post("/catalogo/productos")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void postClienteCreaYSegundaLlamadaConMismaIdentificacionRetorna409() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        CrearClienteRequest request = new CrearClienteRequest(
                "Cliente HTTP", "01", "107890123", null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void patchClienteConIdentificacionInvalidaRetorna400() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        CrearClienteRequest crear = new CrearClienteRequest(
                "Cliente HTTP Patch", "01", "109990123", null, null, null, null, null, null, null, null);

        String cuerpoCreado = mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crear)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(cuerpoCreado).get("id").asText());

        String cuerpoPatch = """
                {"numeroIdentificacion": "123"}
                """;

        mockMvc.perform(patch("/catalogo/clientes/" + id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoPatch))
                .andExpect(status().isBadRequest());
    }

    @Test
    void flujoCompletoDeExoneracionCrearListarYDesactivar() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        CrearClienteRequest crearCliente = new CrearClienteRequest(
                "Cliente Exoneracion HTTP", "02", "3101999999", null, null, null, null, null, null, null, null);

        String cuerpoCliente = mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearCliente)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID clienteId = UUID.fromString(objectMapper.readTree(cuerpoCliente).get("id").asText());

        CrearClienteExoneracionRequest crearExoneracion = new CrearClienteExoneracionRequest(
                "08", "DOC-HTTP-001", "PROCOMER", "1", null, null,
                LocalDateTime.now(), null, new java.math.BigDecimal("100.00"));

        String cuerpoExoneracion = mockMvc.perform(post("/catalogo/clientes/" + clienteId + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearExoneracion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigente").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID exoneracionId = UUID.fromString(objectMapper.readTree(cuerpoExoneracion).get("id").asText());

        mockMvc.perform(get("/catalogo/clientes/" + clienteId + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activo").value(true));

        mockMvc.perform(post("/catalogo/exoneraciones/" + exoneracionId + "/desactivar")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    @Test
    void postExoneracionTipo99SinNombreInstitucionOtrosRetorna400() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        CrearClienteRequest crearCliente = new CrearClienteRequest(
                "Cliente Exoneracion 99 HTTP", "01", "104440123", null, null, null, null, null, null, null, null);

        String cuerpoCliente = mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearCliente)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID clienteId = UUID.fromString(objectMapper.readTree(cuerpoCliente).get("id").asText());

        CrearClienteExoneracionRequest crearExoneracion = new CrearClienteExoneracionRequest(
                "99", "DOC-HTTP-002", "Institución sin catalogar", "1", null, null,
                LocalDateTime.now(), null, new java.math.BigDecimal("50.00"));

        mockMvc.perform(post("/catalogo/clientes/" + clienteId + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearExoneracion)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postExoneracionConClienteYNumeroDocumentoDuplicadosRetorna409() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        CrearClienteRequest crearCliente = new CrearClienteRequest(
                "Cliente Exoneracion Duplicada HTTP", "01", "105550123", null, null, null, null, null, null, null, null);

        String cuerpoCliente = mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearCliente)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID clienteId = UUID.fromString(objectMapper.readTree(cuerpoCliente).get("id").asText());

        CrearClienteExoneracionRequest crearExoneracion = new CrearClienteExoneracionRequest(
                "08", "DOC-DUPLICADO-001", "PROCOMER", "1", null, null,
                LocalDateTime.now(), null, new java.math.BigDecimal("100.00"));

        mockMvc.perform(post("/catalogo/clientes/" + clienteId + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearExoneracion)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/catalogo/clientes/" + clienteId + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearExoneracion)))
                .andExpect(status().isConflict());
    }

    @Test
    void postExoneracionParaClienteDeOtroTenantRetorna404() throws Exception {
        String accessTokenTenantA = crearUsuarioEmpresaYToken();
        String accessTokenTenantB = crearUsuarioEmpresaYToken();

        CrearClienteRequest crearCliente = new CrearClienteRequest(
                "Cliente Tenant A", "01", "101110123", null, null, null, null, null, null, null, null);
        String cuerpoCliente = mockMvc.perform(post("/catalogo/clientes")
                        .header("Authorization", "Bearer " + accessTokenTenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearCliente)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID clienteIdTenantA = UUID.fromString(objectMapper.readTree(cuerpoCliente).get("id").asText());

        CrearClienteExoneracionRequest crearExoneracion = new CrearClienteExoneracionRequest(
                "08", "DOC-CRUZADO-001", "PROCOMER", "1", null, null,
                LocalDateTime.now(), null, new java.math.BigDecimal("100.00"));

        // El cliente pertenece al tenant A, pero se solicita con el token del tenant B.
        mockMvc.perform(post("/catalogo/clientes/" + clienteIdTenantA + "/exoneraciones")
                        .header("Authorization", "Bearer " + accessTokenTenantB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crearExoneracion)))
                .andExpect(status().isNotFound());
    }
}
