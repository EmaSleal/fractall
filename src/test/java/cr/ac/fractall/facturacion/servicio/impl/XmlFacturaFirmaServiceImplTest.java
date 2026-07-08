package cr.ac.fractall.facturacion.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.servicio.XmlFacturaFirmaException;
import cr.ac.fractall.facturacion.servicio.XmlFacturaFirmaService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres + Vault reales vía Testcontainers, sin mocks) de
 * {@link XmlFacturaFirmaServiceImpl} -- Fase 8, sub-tarea "firma digital".
 *
 * <p>A diferencia de la sub-tarea de Oracle Object Storage (mockeada, sin equivalente de
 * contenedor local viable), la firma XAdES-BES es criptografía pura sobre el JDK
 * ({@code javax.xml.crypto.dsig.*}, JSR 105) -- no depende de ninguna red una vez que los bytes del
 * certificado ya se descifraron desde Postgres/Vault (ambos reales aquí, mismo bootstrap de
 * AppRole/Transit que {@code ComprobanteXmlPersistenceServiceTest}/{@code EmpresaFlujoFase5Test}).
 * Se prueba de verdad -- generando un {@code .p12} real vía {@code keytool}, cargándolo por el
 * camino de producción ({@link EmpresaService#cargarCertificado}) y verificando criptográficamente
 * la firma resultante con {@link XmlFacturaFirmaService#verificarFirma(String)} -- en vez de mockear
 * cualquier parte de la cadena.
 */
@Testcontainers
@SpringBootTest
class XmlFacturaFirmaServiceImplTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-firma-1234";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;
    private static byte[] p12ValidoDePrueba;

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

    @BeforeAll
    static void generarCertificadoDePrueba() throws Exception {
        p12ValidoDePrueba = generarP12(PIN_VALIDO);
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

    /**
     * Genera un {@code .p12} autofirmado real vía {@code keytool} del propio JDK que corre la
     * prueba -- mismo enfoque que {@code EmpresaFlujoFase5Test#generarP12}, copiado localmente acá
     * (no vale la pena extraer un utilitario compartido para dos usos, ver el alcance de esta
     * sub-tarea).
     */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-firma-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-firma",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test Firma, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private XmlFacturaFirmaService xmlFacturaFirmaService;

    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba firma XAdES");
        usuario.setEmail("usuario-firma-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa Firma XAdES S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa-firma@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuario.getId());
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void cargarCertificadoValido() {
        empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO);
    }

    private static String xmlDeNegocio(String valorClave) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FacturaElectronica xmlns=\"https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica\">"
                + "<Clave>" + valorClave + "</Clave>"
                + "<NumeroConsecutivo>00100001010000000001</NumeroConsecutivo>"
                + "</FacturaElectronica>";
    }

    @Test
    void firmarProduceUnXmlConFirmaXadesBesCriptograficamenteValida() {
        cargarCertificadoValido();
        String xmlSinFirmar = xmlDeNegocio("50601011900310270100100001010000000001199999999");

        String xmlFirmado = xmlFacturaFirmaService.firmar(xmlSinFirmar, empresa.getId());

        assertThat(xmlFirmado).contains("<ds:Signature");
        assertThat(xmlFirmado).contains("xades:QualifyingProperties");
        assertThat(xmlFirmado).contains("<Clave>50601011900310270100100001010000000001199999999</Clave>");

        assertThat(xmlFacturaFirmaService.verificarFirma(xmlFirmado)).isTrue();
    }

    @Test
    void verificarFirmaDetectaContenidoDeNegocioAlteradoDespuesDeFirmar() {
        cargarCertificadoValido();
        String xmlSinFirmar = xmlDeNegocio("50601011900310270100100001010000000001199999999");

        String xmlFirmado = xmlFacturaFirmaService.firmar(xmlSinFirmar, empresa.getId());
        assertThat(xmlFacturaFirmaService.verificarFirma(xmlFirmado)).isTrue();

        // Se altera UN solo carácter del contenido de negocio (el último dígito de la Clave) ya
        // firmado -- si verificarFirma solo comprobara "existe un <ds:Signature>" sin recalcular
        // los digests reales sobre el contenido, esto pasaría igual como válido.
        String xmlAlterado = xmlFirmado.replace(
                "<Clave>50601011900310270100100001010000000001199999999</Clave>",
                "<Clave>50601011900310270100100001010000000001199999998</Clave>");
        assertThat(xmlAlterado).isNotEqualTo(xmlFirmado);

        assertThat(xmlFacturaFirmaService.verificarFirma(xmlAlterado)).isFalse();
    }

    @Test
    void verificarFirmaConXmlSinFirmarDevuelveFalse() {
        String xmlSinFirmar = xmlDeNegocio("50601011900310270100100001010000000001199999999");
        assertThat(xmlFacturaFirmaService.verificarFirma(xmlSinFirmar)).isFalse();
    }

    @Test
    void firmarSinCertificadoCargadoLanzaXmlFacturaFirmaException() {
        // empresa creada en setUp() nunca pasó por cargarCertificado() -- sin .p12/DEK en Postgres.
        String xmlSinFirmar = xmlDeNegocio("50601011900310270100100001010000000001199999999");

        assertThatThrownBy(() -> xmlFacturaFirmaService.firmar(xmlSinFirmar, empresa.getId()))
                .isInstanceOf(XmlFacturaFirmaException.class);
    }

    @Test
    void firmarConEmpresaInexistenteLanzaIllegalStateException() {
        String xmlSinFirmar = xmlDeNegocio("50601011900310270100100001010000000001199999999");
        UUID empresaInexistente = UUID.randomUUID();

        assertThatThrownBy(() -> xmlFacturaFirmaService.firmar(xmlSinFirmar, empresaInexistente))
                .isInstanceOf(IllegalStateException.class);
    }
}
