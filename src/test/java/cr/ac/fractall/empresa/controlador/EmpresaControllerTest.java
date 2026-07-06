package cr.ac.fractall.empresa.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import cr.ac.fractall.empresa.Empresa;
import cr.ac.fractall.empresa.EmpresaRepository;
import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.ConfigurarCredencialHaciendaRequest;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración a nivel HTTP de los 3 endpoints de {@link EmpresaController} (Fase 5,
 * sección 4.1, 4.2 y 6.4 de {@code arquitectura-facturacion-electronica-cr.md}) -- hasta ahora
 * solo {@code EmpresaFlujoFase5Test} ejercía {@code EmpresaService} directamente; esta clase
 * cierra el vacío de cobertura sobre el controlador real (binding multipart, validación
 * {@code @Valid}, códigos de estado HTTP).
 *
 * <p>Mismo bootstrap de Postgres + Vault vía Testcontainers que {@code EmpresaFlujoFase5Test} y
 * mismo mecanismo para obtener un access token real que {@code AuthFlujoMfaTest}/
 * {@code AuthFlujoLoginYTenantTest} ({@code JwtService#generarToken}, sin pasar por el flujo
 * completo de login/MFA -- esas clases ya prueban ese flujo, no hace falta repetirlo aquí).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EmpresaControllerTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-1234";

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

    /** Genera un {@code .p12} autofirmado real vía {@code keytool} del propio JDK que corre la prueba. */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-controller-test-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-controller-test",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Controller Test, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
            builder.redirectErrorStream(true);
            Process proceso = builder.start();
            String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int codigoSalida = proceso.waitFor();
            assertThat(codigoSalida).as("keytool -genkeypair: " + salida).isZero();
            return Files.readAllBytes(archivo);
        } finally {
            Files.deleteIfExists(archivo);
        }
    }

    private static String rutaKeytool() {
        String ruta = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        return new File(ruta + ".exe").exists() ? ruta + ".exe" : ruta;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtService jwtService;

    /** Crea usuario + empresa en {@code REGISTRADA} y devuelve un access token normal para esa empresa. */
    private String crearUsuarioEmpresaYToken() {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();

            Usuario usuario = new Usuario();
            usuario.setNombre("Persona de prueba EmpresaController");
            usuario.setEmail("empresa-controller-" + UUID.randomUUID() + "@fractall.test");
            usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa de Prueba EmpresaController S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuario.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            return jwtService.generarToken(usuario.getId(), empresa.getId());
        });
    }

    @Test
    void postCertificadoSinArchivoRetorna400NoNoQuinientos() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();

        mockMvc.perform(multipart("/empresa/certificado")
                        .param("pin", PIN_VALIDO)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCertificadoConPinEnBlancoRetorna400() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        byte[] p12 = generarP12(PIN_VALIDO);
        MockMultipartFile archivo = new MockMultipartFile(
                "certificado", "certificado.p12", MediaType.APPLICATION_OCTET_STREAM_VALUE, p12);

        mockMvc.perform(multipart("/empresa/certificado")
                        .file(archivo)
                        .param("pin", "")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchEmpresaActualizaDatosFiscalesParcialmente() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        ActualizarDatosFiscalesRequest request = new ActualizarDatosFiscalesRequest(
                null, "Nombre Comercial HTTP", null, null, null, null, null, null, null, null, null, null);

        mockMvc.perform(patch("/empresa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void postCertificadoConArchivoYPinValidosRetorna200() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        byte[] p12 = generarP12(PIN_VALIDO);
        MockMultipartFile archivo = new MockMultipartFile(
                "certificado", "certificado.p12", MediaType.APPLICATION_OCTET_STREAM_VALUE, p12);

        mockMvc.perform(multipart("/empresa/certificado")
                        .file(archivo)
                        .param("pin", PIN_VALIDO)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void postCredencialesHaciendaRetorna200() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();
        ConfigurarCredencialHaciendaRequest request = new ConfigurarCredencialHaciendaRequest(
                "usuario.hacienda.http@fractall.test", "clave-hacienda-http");

        mockMvc.perform(post("/empresa/credenciales-hacienda")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
