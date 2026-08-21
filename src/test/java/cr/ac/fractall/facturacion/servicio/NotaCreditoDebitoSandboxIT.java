package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaNotaCreditoRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Round-trip REAL contra el ambiente SANDBOX de Hacienda (Release 2 / Fase B, criterio de salida
 * "Opcional SANDBOX round-trip", ver spec y diseño D-H) -- NO forma parte del gate de PR
 * ({@code .github/workflows/tests.yml}). Excluida de {@code ./mvnw test} vía
 * {@code maven-surefire-plugin/excludedGroups=sandbox} (ver {@code pom.xml}); solo corre por
 * {@code workflow_dispatch} manual, o localmente con {@code ./mvnw test -Dgroups=sandbox
 * -DexcludedGroups=}.
 *
 * <p><b>A diferencia de {@link NotaCreditoDebitoEmisionIT}</b> (herméticamente mockeada en la
 * frontera de Hacienda), esta prueba NO mockea {@code HaciendaComprobanteApiService} ni {@code
 * HaciendaApiService} -- ambos corren contra los endpoints reales de sandbox de Hacienda
 * (configurados por defecto en {@code application.yaml}, sección {@code hacienda.oauth.sandbox}).
 * Sí sigue mockeando {@link ObjectStorageService} (sin equivalente de contenedor local para OCI,
 * mismo motivo documentado en {@code OciObjectStorageServiceImpl}) -- el criterio de salida de esta
 * prueba es el ciclo de vida ante Hacienda, no el almacenamiento del XML.
 *
 * <p><b>Secretos esperados</b> (variables de entorno; ninguna existe todavía como GitHub secret al
 * momento de escribir esta clase -- deben aprovisionarse como infraestructura nueva antes de correr
 * el job {@code workflow_dispatch}, ver {@code .github/workflows/nc-nd-sandbox.yml}):
 *
 * <ul>
 *   <li>{@code FRACTALL_SANDBOX_CEDULA} -- cédula jurídica/física del emisor, YA REGISTRADO en el
 *       ambiente SANDBOX de Hacienda (https://api-sandbox.comprobanteselectronicos.go.cr).
 *   <li>{@code FRACTALL_SANDBOX_TIPO_IDENTIFICACION} -- tipo de identificación del emisor (01-04).
 *   <li>{@code FRACTALL_SANDBOX_CODIGO_ACTIVIDAD} -- código de actividad económica registrado.
 *   <li>{@code FRACTALL_SANDBOX_P12_BASE64} -- certificado {@code .p12} real del emisor (el mismo
 *       que Hacienda emitió para ese contribuyente), codificado en Base64. NO puede ser un
 *       certificado autofirmado local -- Hacienda valida la cadena de confianza.
 *   <li>{@code FRACTALL_SANDBOX_P12_PIN} -- PIN del certificado anterior.
 *   <li>{@code FRACTALL_SANDBOX_HACIENDA_USUARIO} -- usuario del API de Hacienda (formato
 *       {@code cpj-cedula-99} o {@code cpf-cedula-99} según tipo de identificación).
 *   <li>{@code FRACTALL_SANDBOX_HACIENDA_PASSWORD} -- contraseña del API de Hacienda.
 * </ul>
 *
 * <p>Si cualquiera de estas variables falta, la prueba se SALTA (no falla) vía
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue}, con un mensaje que lista exactamente qué
 * falta -- así un {@code workflow_dispatch} corrido antes de aprovisionar los secretos falla de
 * forma clara y accionable en vez de un error de aserción confuso.
 *
 * <p>Excluida por defecto vía {@code maven-surefire-plugin}/{@code excludedGroups=sandbox} (ver
 * {@code pom.xml}) -- para incluirla explícitamente: {@code ./mvnw test -Dgroups=sandbox
 * -DexcludedGroups=} (el override vacío es necesario porque {@code -Dgroups=sandbox} sin también
 * vaciar {@code excludedGroups} filtraría "incluir sandbox Y excluir sandbox" = 0 pruebas).
 */
@Tag("sandbox")
@Testcontainers
@SpringBootTest
class NotaCreditoDebitoSandboxIT {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

    /** Máximo de sondeos de {@code consultarYActualizar} antes de dar por fallido el round-trip. */
    private static final int INTENTOS_MAXIMOS_SONDEO = 12;
    /** Espera entre sondeos -- Hacienda SANDBOX suele tardar varios segundos en procesar. */
    private static final long ESPERA_ENTRE_SONDEOS_MS = 5_000L;

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

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private FacturaService facturaService;

    @Autowired
    private ComprobanteEmisionService comprobanteEmisionService;

    @Autowired
    private NotaCreditoDebitoService notaCreditoDebitoService;

    @Autowired
    private ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    // Sin equivalente de contenedor local para OCI -- ver el javadoc de la clase y de
    // OciObjectStorageServiceImpl. Todo lo demás (HaciendaApiService, HaciendaComprobanteApiService)
    // corre real contra sandbox.
    @MockitoBean
    private ObjectStorageService objectStorageService;

    private UUID usuarioId;
    private Empresa empresa;
    private Cliente cliente;

    @BeforeEach
    void setUp() {
        assumeTrue(variablesDeEntornoPresentes(),
                "NotaCreditoDebitoSandboxIT saltada: faltan una o más variables de entorno de "
                        + "SANDBOX (ver el javadoc de la clase para la lista completa): "
                        + variablesFaltantes());

        when(objectStorageService.subir(any(byte[].class), anyString()))
                .thenReturn("empresas/sandbox-it/comprobantes/objeto-de-prueba.xml.enc");

        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario SANDBOX NC/ND IT");
        usuario.setEmail("usuario-sandbox-nc-nd-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);
        usuarioId = usuario.getId();

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa SANDBOX NC/ND IT");
        nueva.setNumeroIdentificacion(env("FRACTALL_SANDBOX_CEDULA"));
        nueva.setTipoIdentificacion(env("FRACTALL_SANDBOX_TIPO_IDENTIFICACION"));
        nueva.setCodigoActividad(env("FRACTALL_SANDBOX_CODIGO_ACTIVIDAD"));
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("Dirección de prueba SANDBOX IT");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa-sandbox-nc-nd@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));

        byte[] p12Real = Base64.getDecoder().decode(env("FRACTALL_SANDBOX_P12_BASE64"));
        empresaService.cargarCertificado(p12Real, env("FRACTALL_SANDBOX_P12_PIN"), "SANDBOX");
        empresaService.configurarCredencialHacienda(
                env("FRACTALL_SANDBOX_HACIENDA_USUARIO"),
                env("FRACTALL_SANDBOX_HACIENDA_PASSWORD"),
                "SANDBOX", usuarioId);

        cliente = new Cliente();
        cliente.setNombre("Cliente SANDBOX NC/ND IT");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        cliente = clienteRepository.save(cliente);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static final String[] VARIABLES_REQUERIDAS = {
        "FRACTALL_SANDBOX_CEDULA", "FRACTALL_SANDBOX_TIPO_IDENTIFICACION",
        "FRACTALL_SANDBOX_CODIGO_ACTIVIDAD", "FRACTALL_SANDBOX_P12_BASE64",
        "FRACTALL_SANDBOX_P12_PIN", "FRACTALL_SANDBOX_HACIENDA_USUARIO",
        "FRACTALL_SANDBOX_HACIENDA_PASSWORD"
    };

    private static boolean variablesDeEntornoPresentes() {
        for (String variable : VARIABLES_REQUERIDAS) {
            String valor = System.getenv(variable);
            if (valor == null || valor.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String variablesFaltantes() {
        StringBuilder sb = new StringBuilder();
        for (String variable : VARIABLES_REQUERIDAS) {
            String valor = System.getenv(variable);
            if (valor == null || valor.isBlank()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(variable);
            }
        }
        return sb.toString();
    }

    private static String env(String nombre) {
        return System.getenv(nombre);
    }

    private Producto crearProducto(BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-SANDBOX-IT-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba SANDBOX IT");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(porcentajeImpuesto.compareTo(BigDecimal.ZERO) > 0);
        producto.setPorcentajeImpuesto(porcentajeImpuesto);
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        return productoRepository.save(producto);
    }

    /**
     * Somete una factura tipo 01 al pipeline REAL de emisión (mismo camino que
     * {@code FacturaController.crear}: {@code facturaService.crear} + {@code
     * comprobanteEmisionService.procesarXmlYEnvio}) y hace sondeo acotado hasta {@code ACEPTADO}.
     */
    private FacturaResponse emitirFacturaOrigenYEsperarAceptado() {
        Producto producto = crearProducto(new BigDecimal("13.00"));
        LineaFacturaItemRequest linea = new LineaFacturaItemRequest(
                producto.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                null, null, null, null, null, null, null, null);
        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                null, "CRC", null, List.of(linea), null, null, null);

        FacturaResponse response = facturaService.crear(request);
        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());
        esperarHastaAceptado(response.comprobanteId());
        return response;
    }

    /** Sondeo acotado -- ver el javadoc de la clase (bounded polling del criterio de salida). */
    private void esperarHastaAceptado(UUID comprobanteId) {
        for (int intento = 0; intento < INTENTOS_MAXIMOS_SONDEO; intento++) {
            ComprobanteElectronico actual = comprobanteElectronicoRepository.findById(comprobanteId).orElseThrow();
            if ("ACEPTADO".equals(actual.getEstado())) {
                return;
            }
            if ("RECHAZADO".equals(actual.getEstado())) {
                fail("Comprobante " + comprobanteId + " fue RECHAZADO por Hacienda SANDBOX: "
                        + actual.getMensajeRespuesta());
            }
            try {
                Thread.sleep(ESPERA_ENTRE_SONDEOS_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Sondeo interrumpido para comprobante " + comprobanteId);
            }
            comprobanteHaciendaEnvioService.consultarYActualizar(actual);
        }
        fail("Comprobante " + comprobanteId + " no llegó a ACEPTADO tras " + INTENTOS_MAXIMOS_SONDEO
                + " sondeos (" + (INTENTOS_MAXIMOS_SONDEO * ESPERA_ENTRE_SONDEOS_MS / 1000) + "s)");
    }

    @Test
    void notaCreditoRealContraSandboxLlegaAAceptado() {
        FacturaResponse origen = emitirFacturaOrigenYEsperarAceptado();

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.id(), "02", null, "Corrección SANDBOX IT",
                List.of(new LineaNotaCreditoRequest(origen.lineas().get(0).id(), BigDecimal.ONE)));

        FacturaResponse nc = notaCreditoDebitoService.crearNotaCredito(request);
        comprobanteEmisionService.procesarXmlYEnvio(nc.comprobanteId());
        esperarHastaAceptado(nc.comprobanteId());

        assertThat(comprobanteElectronicoRepository.findById(nc.comprobanteId()).orElseThrow().getEstado())
                .isEqualTo("ACEPTADO");
    }

    @Test
    void notaDebitoRealContraSandboxLlegaAAceptado() {
        FacturaResponse origen = emitirFacturaOrigenYEsperarAceptado();
        Producto productoNd = crearProducto(new BigDecimal("13.00"));

        CrearNotaDebitoRequest request = new CrearNotaDebitoRequest(
                origen.id(), "01", null, "Cargo olvidado SANDBOX IT",
                List.of(new LineaFacturaItemRequest(productoNd.getId(), BigDecimal.ONE, new BigDecimal("500.00000"),
                        null, null, null, null, null, null, null, null)));

        FacturaResponse nd = notaCreditoDebitoService.crearNotaDebito(request);
        comprobanteEmisionService.procesarXmlYEnvio(nd.comprobanteId());
        esperarHastaAceptado(nd.comprobanteId());

        assertThat(comprobanteElectronicoRepository.findById(nd.comprobanteId()).orElseThrow().getEstado())
                .isEqualTo("ACEPTADO");
    }
}
