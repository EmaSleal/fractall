package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.dto.CrearTiqueteRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.notificaciones.servicio.ResendEmailClient;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba hermética de extremo a extremo del criterio de salida de Fase C (Release 2, ver
 * {@code docs/plan-fases-release-2.md}): un Tiquete Electrónico SIN receptor identificado debe
 * pasar {@code GENERADO -> FIRMADO -> ENVIADO -> ACEPTADO} en {@code SANDBOX}, (a) sin disparar el
 * trigger de aislamiento multi-tenant por error (el riesgo crítico de {@code fn_validar_mismo_tenant},
 * ver V19), y (b) sin intentar entrega por correo cuando no hay cliente. Mismo bootstrap de
 * Postgres + Vault (Testcontainers, firma XAdES-BES real, validación XSD real) que {@code
 * NotaCreditoDebitoEmisionIT} -- ver su javadoc para el detalle general de qué se mockea y por qué.
 *
 * <p>Diferencia deliberada frente a {@code NotaCreditoDebitoEmisionIT}: ahí {@code
 * ComprobanteEntregaService} completo está mockeado (la entrega no es el foco de esa prueba). Acá
 * NO se mockea -- es real, mismo patrón que {@code ComprobanteEntregaServiceIntegrationTest} --
 * porque la parte (b) del criterio de salida es precisamente sobre su comportamiento con {@code
 * clienteId == null}: mockearlo por completo ocultaría el guard bajo prueba (
 * {@code ComprobanteHaciendaEnvioService#entregarSiAceptado} lo invoca sin condicionar por
 * clienteId, así que un mock de la clase completa jamás ejercitaría ese guard). Solo se mockea su
 * hoja de red ({@link ResendEmailClient}) para poder aseverar que nunca se invoca.
 */
@Testcontainers
@SpringBootTest
class TiqueteEmisionIT {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-tiquete-emision-1234";

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

    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-tiquete-emision-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-tiquete-emision",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test Tiquete Emision, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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
    private ProductoRepository productoRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private TiqueteService tiqueteService;

    @Autowired
    private ComprobanteEmisionService comprobanteEmisionService;

    @Autowired
    private XmlFacturaGeneratorService xmlFacturaGeneratorService;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @MockitoBean
    private HaciendaComprobanteApiService haciendaComprobanteApiService;

    @MockitoBean
    private HaciendaApiService haciendaApiService;

    @MockitoBean
    private ResendEmailClient resendEmailClient;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba emisión Tiquete");
        usuario.setEmail("usuario-emision-tiquete-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Emisión Tiquete S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa-emision-tiquete@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));

        empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO, "SANDBOX");

        LocalDateTime ahora = LocalDateTime.now();
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresa.getId());
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda("usuario-" + empresa.getId() + "@hacienda.test");
        credencial.setCredencialReferencia(
                "secret/data/empresas/" + empresa.getId() + "/hacienda/sandbox/password");
        credencial.setConfiguradaEn(ahora);
        credencial.setConfiguradaPor(usuarioId);
        credencialHaciendaRepository.save(credencial);

        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenAnswer(invocacion -> RespuestaHaciendaDTO.builder()
                        .claveNumerica(invocacion.getArgument(1))
                        .fechaRespuesta(LocalDateTime.now())
                        .codigoMensaje(MensajeHacienda.ACEPTADO)
                        .mensaje("Comprobante aceptado")
                        .exitoso(true)
                        .debeReintentar(false)
                        .codigoHttp(200)
                        .build());

        when(objectStorageService.subir(any(byte[].class), anyString()))
                .thenReturn("empresas/" + empresa.getId() + "/comprobantes/objeto-de-prueba.xml.enc");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Producto crearProducto(BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-TIQ-IT-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba emisión Tiquete");
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
     * Criterio de salida de Fase C, las 3 partes en un único test hermético: (1) un Tiquete SIN
     * receptor identificado llega a ACEPTADO en SANDBOX vía el ciclo completo GENERADO -> FIRMADO
     * -> ENVIADO -> ACEPTADO; (2) eso implica que el INSERT de la fila {@code factura} con
     * {@code cliente_id = NULL} no disparó el trigger de aislamiento multi-tenant por error --
     * si lo hubiera disparado, {@code tiqueteService.crear} habría lanzado antes de llegar acá
     * (mismo riesgo que {@code AislamientoMultiTenantTest#facturaConClienteIdNuloNoDisparaElTriggerDeAislamientoTenant}
     * prueba de forma aislada a nivel de motor); (3) al llegar a ACEPTADO, {@code
     * ComprobanteHaciendaEnvioService} (real) dispara {@code ComprobanteEntregaService#entregar}
     * (real, ver el javadoc de la clase) incondicionalmente -- el guard de {@code clienteId ==
     * null} corta la Fase B de esa entrega ANTES de resolver ningún cliente, así que
     * {@link ResendEmailClient} nunca debe ser invocado.
     */
    @Test
    void tiqueteSinReceptorLlegaAAceptadoSinDispararTriggerTenantYSinEntregaPorCorreo() {
        Producto producto = crearProducto(new BigDecimal("13.00"));

        CrearTiqueteRequest request = new CrearTiqueteRequest(
                null, null, null, null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                        null, null, null, null, null, null, null, null)));

        FacturaResponse response = tiqueteService.crear(request);
        assertThat(response.tipoComprobante()).isEqualTo("04");
        assertThat(response.clienteId()).isNull();
        assertThat(response.estado()).isEqualTo("GENERADO");

        Factura persistida = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(persistida.getClienteId()).isNull();

        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());

        ComprobanteElectronico releido =
                comprobanteElectronicoRepository.findById(response.comprobanteId()).orElseThrow();
        assertThat(releido.getEstado()).isEqualTo("ACEPTADO");

        String xml = xmlFacturaGeneratorService.generarXmlFactura(response.comprobanteId());
        assertThat(xml).contains("<TiqueteElectronico");
        assertThat(xml).contains(" xmlns=\"" + TipoComprobantePerfil.TIQUETE.namespace() + "\"");
        assertThat(xml).doesNotContain("<Receptor>");

        // ComprobanteEntregaService es real (ver el javadoc de la clase) y SÍ fue invocado por
        // ComprobanteHaciendaEnvioService al llegar a ACEPTADO -- lo que esta aserción confirma es
        // que su guard de clienteId == null cortó ANTES de llegar a resolver un cliente/enviar
        // correo: ResendEmailClient (su única hoja de red mockeada) nunca fue invocado.
        verifyNoInteractions(resendEmailClient);
    }
}
