package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaNotaCreditoRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba hermética de extremo a extremo del criterio de salida de Fase B (Release 2 / Fase B,
 * diseño D-H): una Nota de Crédito parcial y una Nota de Débito completa deben pasar
 * {@code GENERADO -> FIRMADO -> ENVIADO -> ACEPTADO} sin intervención manual, con firma XML-DSig
 * real y validación XSD real contra los schemas de NC/ND, dentro de {@code ./mvnw test}.
 *
 * <p>Mismo bootstrap de Postgres + Vault (Testcontainers, AppRole/Transit reales) que {@code
 * ComprobanteXmlPersistenceServiceTest}/{@code FacturaControllerTest} -- la firma XAdES-BES corre
 * de punta a punta sobre un certificado {@code .p12} autofirmado real (generado vía {@code
 * keytool}, mismo enfoque documentado en {@code XmlFacturaFirmaServiceImplTest}). Solo se mockean
 * las hojas que tocarían red externa o que no tienen equivalente local:
 *
 * <ul>
 *   <li>{@link ObjectStorageService} -- sin equivalente de contenedor local para OCI (ver el
 *       javadoc de {@code OciObjectStorageServiceImpl}).
 *   <li>{@link HaciendaComprobanteApiService} -- devuelve una respuesta {@code ACEPTADO}
 *       determinística, así {@code ComprobanteHaciendaEnvioService} (real, no mockeado) conduce la
 *       transición {@code ENVIADO -> ACEPTADO} sin llamada de red real. La credencial de Hacienda
 *       que ese servicio busca SÍ es una fila real de {@code CredencialHacienda} persistida en
 *       {@link #setUp()} (mismo patrón que {@code FacturaControllerTest}).
 *   <li>{@link HaciendaApiService} -- ninguna de las dos operaciones bajo prueba lo invoca (moneda
 *       CRC, sin resolución de tipo de cambio), pero se mockea igual como cinturón de seguridad
 *       contra cualquier llamada de red real desde un job {@code @Scheduled} que pudiera correr
 *       durante el contexto de esta prueba.
 *   <li>{@code ComprobanteEntregaService} -- {@code entregarSiAceptado} dispara entrega
 *       (PDF+correo) en cuanto el estado llega a {@code ACEPTADO}; mockeado para mantener esta
 *       prueba enfocada en la emisión, no en la entrega (que tiene su propia suite, {@code
 *       ComprobanteEntregaServiceIntegrationTest}).
 * </ul>
 *
 * <p>La factura origen (tipo 01) NO pasa por su propio ciclo real de envío a Hacienda -- se
 * registra vía {@link ComprobanteEmisionService#registrarComprobante} y su estado se fuerza
 * directamente a {@code ACEPTADO} (mismo atajo que {@code NotaCreditoDebitoServiceTest}), porque
 * el criterio de salida de Fase B es sobre el ciclo de vida de la NC/ND, no del origen -- el origen
 * ya ACEPTADO es una precondición, no lo que está bajo prueba.
 */
@Testcontainers
@SpringBootTest
class NotaCreditoDebitoEmisionIT {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-nc-nd-emision-1234";

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

    /**
     * Genera un {@code .p12} autofirmado real vía {@code keytool} del propio JDK que corre la
     * prueba -- mismo enfoque que {@code ComprobanteXmlPersistenceServiceTest#generarP12}, copiado
     * localmente acá.
     */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-nc-nd-emision-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-nc-nd-emision",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test NC-ND Emision, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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
    private ClienteRepository clienteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private LineaFacturaRepository lineaFacturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotaCreditoDebitoService notaCreditoDebitoService;

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
    private ComprobanteEntregaService comprobanteEntregaService;

    private UUID usuarioId;
    private Empresa empresa;
    private Cliente cliente;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba emisión NC/ND");
        usuario.setEmail("usuario-emision-nc-nd-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Emisión NC/ND S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa-emision-nc-nd@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));

        // Certificado .p12 real cargado por el camino de producción -- ver el javadoc de la clase:
        // generarYPersistirXml firma el XML antes de subirlo.
        empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO, "SANDBOX");

        // Credencial de Hacienda real (fila persistida), necesaria para que
        // ComprobanteHaciendaEnvioService (NO mockeado) encuentre un CredencialHacienda -- mismo
        // patrón que FacturaControllerTest.
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

        // Respuesta determinística de "ACEPTADO" -- conduce ENVIADO -> ACEPTADO sin red real (ver
        // el javadoc de la clase). Válida para ambos escenarios felices (NC y ND); los escenarios
        // de rechazo (c/d) nunca llegan a invocar esto, se rechazan en Java antes de cualquier
        // escritura.
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

        cliente = new Cliente();
        cliente.setNombre("Cliente de prueba emisión NC/ND");
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

    private Producto crearProducto(BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-EMISION-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba emisión NC/ND");
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

    /** Fixture: factura origen tipo 01 + su única línea + su comprobante_electronico. */
    private record FacturaOrigenFixture(Factura factura, LineaFactura linea, ComprobanteElectronico comprobante) {}

    /**
     * Crea una factura origen tipo 01 con una única línea, y su {@code ComprobanteElectronico} en
     * el estado indicado -- {@code ACEPTADO} en el camino feliz, {@code GENERADO} para probar la
     * regla 1. Mismo patrón que {@code NotaCreditoDebitoServiceTest#crearFacturaOrigenConLinea}.
     */
    private FacturaOrigenFixture crearFacturaOrigenConLinea(
            BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal porcentajeImpuesto, String estado) {
        BigDecimal montoTotalLinea = cantidad.multiply(precioUnitario).setScale(5, java.math.RoundingMode.HALF_UP);
        BigDecimal subtotalLinea = montoTotalLinea;
        BigDecimal impuestoLinea = subtotalLinea.multiply(porcentajeImpuesto)
                .divide(BigDecimal.valueOf(100), 5, java.math.RoundingMode.HALF_UP);
        BigDecimal totalFactura = subtotalLinea.add(impuestoLinea).setScale(5, java.math.RoundingMode.HALF_UP);

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(subtotalLinea);
        factura.setTotalImpuesto(impuestoLinea);
        factura.setTotal(totalFactura);
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        facturaRepository.saveAndFlush(factura);

        Producto producto = crearProducto(porcentajeImpuesto);
        LineaFactura linea = new LineaFactura();
        linea.setFacturaId(factura.getId());
        linea.setProductoId(producto.getId());
        linea.setNumeroLinea(1);
        linea.setCantidad(cantidad);
        linea.setPrecioUnitario(precioUnitario);
        linea.setSubtotal(subtotalLinea);
        linea.setCodigoCabysAplicado(producto.getCodigoCabys());
        linea.setGravadoAplicado(true);
        linea.setPorcentajeImpuestoAplicado(porcentajeImpuesto);
        linea.setTipoTransaccion("01");
        linea.setIvaCobradoFabrica(BigDecimal.ZERO);
        lineaFacturaRepository.saveAndFlush(linea);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ComprobanteElectronico comprobante = transactionTemplate.execute(status ->
                comprobanteEmisionService.registrarComprobante(
                        factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa,
                        ZonedDateTime.now(ZoneOffset.UTC)));
        comprobante.setEstado(estado);
        comprobanteElectronicoRepository.save(comprobante);

        return new FacturaOrigenFixture(factura, linea, comprobante);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario (a): NC parcial sobre factura ACEPTADO -- ciclo completo hasta ACEPTADO
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void notaCreditoParcialSobreFacturaAceptadaLlegaAAceptadoConXmlValido() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), "ACEPTADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección parcial (IT hermética)",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), new BigDecimal("4"))));

        FacturaResponse response = notaCreditoDebitoService.crearNotaCredito(request);
        assertThat(response.tipoComprobante()).isEqualTo("03");
        assertThat(response.estado()).isEqualTo("GENERADO");

        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());

        ComprobanteElectronico releido =
                comprobanteElectronicoRepository.findById(response.comprobanteId()).orElseThrow();
        assertThat(releido.getEstado()).isEqualTo("ACEPTADO");

        // Generación + validación XSD real (esto es el foco de la fase, ver el javadoc D-H) --
        // se invoca de nuevo directamente (idempotente, no muta nada) para inspeccionar el XML sin
        // tener que descifrar el blob subido a Object Storage.
        String xml = xmlFacturaGeneratorService.generarXmlFactura(response.comprobanteId());
        assertThat(xml).contains("<NotaCreditoElectronica");
        assertThat(xml).contains(" xmlns=\"" + TipoComprobantePerfil.NOTA_CREDITO.namespace() + "\"");
        assertThat(xml).containsOnlyOnce("<InformacionReferencia>");
        assertThat(xml).contains(
                "<InformacionReferencia><TipoDocIR>01</TipoDocIR><Numero>"
                        + origen.comprobante().getClaveNumerica() + "</Numero>");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario (b): ND con línea de catálogo -- ciclo completo hasta ACEPTADO
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void notaDebitoConLineaDeCatalogoLlegaAAceptadoConXmlValido() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.ONE, new BigDecimal("1000.00000"), new BigDecimal("13.00"), "ACEPTADO");
        Producto productoNd = crearProducto(new BigDecimal("13.00"));

        CrearNotaDebitoRequest request = new CrearNotaDebitoRequest(
                origen.factura().getId(), "01", null, "Cargo olvidado (IT hermética)",
                List.of(new LineaFacturaItemRequest(productoNd.getId(), BigDecimal.ONE, new BigDecimal("500.00000"),
                        null, null, null, null, null, null, null, null)));

        FacturaResponse response = notaCreditoDebitoService.crearNotaDebito(request);
        assertThat(response.tipoComprobante()).isEqualTo("02");
        assertThat(response.estado()).isEqualTo("GENERADO");

        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());

        ComprobanteElectronico releido =
                comprobanteElectronicoRepository.findById(response.comprobanteId()).orElseThrow();
        assertThat(releido.getEstado()).isEqualTo("ACEPTADO");

        String xml = xmlFacturaGeneratorService.generarXmlFactura(response.comprobanteId());
        assertThat(xml).contains("<NotaDebitoElectronica");
        assertThat(xml).contains(" xmlns=\"" + TipoComprobantePerfil.NOTA_DEBITO.namespace() + "\"");
        assertThat(xml).containsOnlyOnce("<InformacionReferencia>");
        assertThat(xml).contains(
                "<InformacionReferencia><TipoDocIR>01</TipoDocIR><Numero>"
                        + origen.comprobante().getClaveNumerica() + "</Numero>");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario (c): origen no ACEPTADO -- rechazado antes de escribir nada
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void notaCreditoSobreFacturaNoAceptadaEsRechazadaSinEscribirNada() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), "GENERADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Intento inválido (IT hermética)",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));

        long facturasAntes = facturaRepository.count();

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(FacturaOrigenNoAceptadaException.class);

        assertThat(facturaRepository.count()).isEqualTo(facturasAntes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario (d): NC referenciando otra NC -- rechazada en Java, antes del trigger V18
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void notaCreditoSobreOtraNotaCreditoEsRechazadaEnJavaAntesDelTriggerV18() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), "ACEPTADO");
        // Simula que el origen es en realidad otra NC/ND (tipo_comprobante='03') -- regla 7. El
        // trigger V18 de defensa en profundidad nunca llega a evaluarse: el pre-chequeo de Java
        // (resolverYValidarOrigen) rechaza antes de cualquier INSERT.
        origen.comprobante().setTipoComprobante("03");
        comprobanteElectronicoRepository.save(origen.comprobante());

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Intento inválido (IT hermética)",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));

        long facturasAntes = facturaRepository.count();

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(ReferenciaNoEsFacturaElectronicaException.class);

        assertThat(facturaRepository.count()).isEqualTo(facturasAntes);
    }
}
