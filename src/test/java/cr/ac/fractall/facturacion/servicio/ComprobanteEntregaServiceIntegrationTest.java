package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.notificaciones.servicio.ResendEmailClient;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;

/**
 * Prueba de integración de punta a punta de la Fase 9 (T-08): verifica que el flujo completo
 * {@code ComprobanteHaciendaEnvioService.enviarComprobante()} → ACEPTADO → {@code entregarSiAceptado()}
 * → {@code ComprobanteEntregaService.entregar()} termina con {@code pdf_referencia} persistido en
 * la base de datos real (Postgres vía Testcontainers) y que {@link ResendEmailClient} es invocado.
 *
 * <p>Wiring comprobado de punta a punta: el estado ACEPTADO de Hacienda (mockeado) dispara la
 * entrega, que genera el PDF (PDFBox real), lo cifra (Vault real), lo "sube" a OCI (mockeado) y
 * persiste la referencia. El XML descifrado que construye el descargador también usa Vault real --
 * el blob de prueba se arma con {@link cr.ac.fractall.facturacion.servicio.ComprobanteXmlBlobFormat}
 * y {@link EnvelopeCipher}, mismas primitivas que el Uploader usa en producción.
 *
 * <p>Mocks deliberados:
 * <ul>
 *   <li>{@link ObjectStorageService}: Oracle OCI no tiene imagen local disponible (misma
 *       restricción documentada en {@code OciObjectStorageServiceImpl}).
 *   <li>{@link HaciendaComprobanteApiService}: evita llamadas HTTP a Hacienda.
 *   <li>{@link ResendEmailClient}: evita envío real de correo; verificamos su invocación.
 * </ul>
 */
@Testcontainers
@SpringBootTest
class ComprobanteEntregaServiceIntegrationTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-entrega-integracion-1234";

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
        Path archivo = Files.createTempFile("fractall-test-entrega-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-entrega",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test Entrega, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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
        secretId = ejecutarVault("write", "-field=secret_id", "-f",
                "auth/approle/role/" + APPROLE_NAME + "/secret-id")
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

    // -------------------------------------------------------------------------
    // Injected beans
    // -------------------------------------------------------------------------

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
    private ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private TransitService transitService;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @MockitoBean
    private HaciendaComprobanteApiService haciendaComprobanteApiService;

    @MockitoBean
    private ResendEmailClient resendEmailClient;

    // -------------------------------------------------------------------------
    // Test state
    // -------------------------------------------------------------------------

    private Empresa empresa;
    private UUID usuarioId;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID()); // descarte para abrir EntityManager

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba entrega fase-9");
        usuario.setEmail("usuario-entrega-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Entrega Fase9 S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa-entrega@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());

        // Cargar certificado real (necesario para que ComprobanteXmlPersistenceService firme el XML
        // si se encadenara ese flujo; aquí el XML ya está firmado, pero el contexto Vault sí es
        // real para cifrado/descifrado del PDF en ComprobanteEntregaService)
        empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO);

        // Credencial de Hacienda necesaria para ComprobanteHaciendaEnvioService
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresa.getId());
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda("usuario@hacienda.test");
        credencial.setCredencialReferencia("secret/data/empresas/" + empresa.getId() + "/hacienda/sandbox/password");
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(usuarioId);
        credencialHaciendaRepository.save(credencial);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helper: build a real encrypted XML blob using Vault Transit (same as prod)
    // -------------------------------------------------------------------------

    private byte[] construirBlobCifrado(String xmlClaro) {
        TransitService.Dek dek = transitService.generarDek();
        byte[] xmlCifrado;
        try {
            xmlCifrado = EnvelopeCipher.cifrar(dek.plaintext(), xmlClaro.getBytes(StandardCharsets.UTF_8));
        } finally {
            java.util.Arrays.fill(dek.plaintext(), (byte) 0);
        }
        return ComprobanteXmlBlobFormat.serializar(dek.cifrado(), xmlCifrado);
    }

    // -------------------------------------------------------------------------
    // Helper: create a ComprobanteElectronico with its full dependency graph
    // -------------------------------------------------------------------------

    private ComprobanteElectronico crearComprobanteConFactura(String emailCliente) {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente Entrega Fase9");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setEmail(emailCliente);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        cliente = clienteRepository.save(cliente);

        Producto producto = new Producto();
        producto.setCodigo("PROD-ENT-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba entrega fase-9");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripcion CABYS de prueba");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(true);
        producto.setPorcentajeImpuesto(new BigDecimal("13.00"));
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        producto = productoRepository.save(producto);

        BigDecimal subtotal = new BigDecimal("1000.00000");
        BigDecimal totalImpuesto = new BigDecimal("130.00000");

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(BigDecimal.ONE);
        factura.setSubtotal(subtotal);
        factura.setTotalImpuesto(totalImpuesto);
        factura.setTotal(subtotal.add(totalImpuesto));
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        factura = facturaRepository.saveAndFlush(factura);

        LineaFactura linea = new LineaFactura();
        linea.setFacturaId(factura.getId());
        linea.setProductoId(producto.getId());
        linea.setNumeroLinea(1);
        linea.setCantidad(BigDecimal.ONE);
        linea.setPrecioUnitario(producto.getPrecioVenta());
        linea.setSubtotal(subtotal);
        linea.setCodigoCabysAplicado(producto.getCodigoCabys());
        linea.setGravadoAplicado(producto.isGravado());
        linea.setPorcentajeImpuestoAplicado(producto.getPorcentajeImpuesto());
        lineaFacturaRepository.saveAndFlush(linea);

        String consecutivo = String.format(
                "%020d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000_000_000_000L));
        String relleno = UUID.randomUUID().toString().replaceAll("[^0-9]", "");
        while (relleno.length() < 30) {
            relleno = relleno + UUID.randomUUID().toString().replaceAll("[^0-9]", "");
        }
        String claveNumerica = ("506" + consecutivo + relleno).substring(0, 50);

        // Build a real encrypted XML blob (same pipeline as production)
        byte[] xmlComprobanteBlob = construirBlobCifrado("<FacturaElectronica><Clave>" + claveNumerica + "</Clave></FacturaElectronica>");
        String xmlComprobanteRef = "empresas/" + empresa.getId() + "/comprobantes/" + claveNumerica + ".xml.enc";

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante("01");
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("FIRMADO");
        comprobante.setXmlComprobanteReferencia(xmlComprobanteRef);
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        comprobante = comprobanteElectronicoRepository.saveAndFlush(comprobante);

        // Wire OCI mock to return the real encrypted blob when the descargador asks for it
        when(objectStorageService.descargar(xmlComprobanteRef)).thenReturn(xmlComprobanteBlob);

        return comprobante;
    }

    // -------------------------------------------------------------------------
    // T-08a: ACEPTADO via enviarComprobante → entregar fires → pdf_referencia persisted
    // -------------------------------------------------------------------------

    @Test
    void aceptadoViaEnvioDisparaEntregaYPersistePdfReferencia() {
        ComprobanteElectronico comprobante = crearComprobanteConFactura("cliente-entrega@test.com");

        String expectedPdfRef = "empresas/" + empresa.getId()
                + "/comprobantes/" + comprobante.getClaveNumerica() + ".pdf.enc";

        // Hacienda mock: responde ACEPTADO con un XML de respuesta
        String xmlRespuestaBase64 = java.util.Base64.getEncoder()
                .encodeToString("<MensajeHacienda/>".getBytes(StandardCharsets.UTF_8));
        when(haciendaComprobanteApiService.enviarComprobante(anyString(), anyString(), any()))
                .thenReturn(RespuestaHaciendaDTO.builder()
                        .claveNumerica(comprobante.getClaveNumerica())
                        .fechaRespuesta(LocalDateTime.now())
                        .codigoMensaje(MensajeHacienda.ACEPTADO)
                        .mensaje("Aceptado")
                        .xmlRespuesta(xmlRespuestaBase64)
                        .exitoso(true)
                        .debeReintentar(false)
                        .codigoHttp(200)
                        .build());

        // OCI mock: subir returns a deterministic reference for the XML respuesta upload
        String xmlRespuestaRef = "empresas/" + empresa.getId()
                + "/comprobantes/" + comprobante.getClaveNumerica() + "-respuesta.xml.enc";
        // Build a real blob for the respuesta XML (needed when descargador downloads it for the email)
        byte[] xmlRespuestaBlob = construirBlobCifrado("<MensajeHacienda/>");
        when(objectStorageService.descargar(xmlRespuestaRef)).thenReturn(xmlRespuestaBlob);
        when(objectStorageService.subir(any(byte[].class), anyString()))
                .thenAnswer(inv -> (String) inv.getArguments()[1]);

        // ResendEmailClient mock: accept any call, return true
        when(resendEmailClient.enviar(anyString(), anyString(), anyString(), anyList())).thenReturn(true);

        // Trigger: sync send to Hacienda (ACEPTADO) → wiring fires entregar
        comprobanteHaciendaEnvioService.enviarComprobante("<xml-firmado-de-prueba/>", comprobante);

        // Verify: pdf_referencia persisted in real DB
        ComprobanteElectronico releido = comprobanteElectronicoRepository
                .findById(comprobante.getId())
                .orElseThrow();
        assertThat(releido.getEstado()).isEqualTo("ACEPTADO");
        assertThat(releido.getPdfReferencia()).isEqualTo(expectedPdfRef);

        // Verify: email was sent with the real ResendEmailClient mock
        verify(resendEmailClient).enviar(
                anyString(), anyString(), anyString(), anyList());
    }

    // -------------------------------------------------------------------------
    // T-08b: cliente.email null — pdf_referencia persisted, no exception
    // -------------------------------------------------------------------------

    @Test
    void clienteEmailNuloNoPropagaExcepcionYPdfReferenciaPersistida() {
        ComprobanteElectronico comprobante = crearComprobanteConFactura(null); // null email

        String expectedPdfRef = "empresas/" + empresa.getId()
                + "/comprobantes/" + comprobante.getClaveNumerica() + ".pdf.enc";

        when(haciendaComprobanteApiService.enviarComprobante(anyString(), anyString(), any()))
                .thenReturn(RespuestaHaciendaDTO.builder()
                        .claveNumerica(comprobante.getClaveNumerica())
                        .fechaRespuesta(LocalDateTime.now())
                        .codigoMensaje(MensajeHacienda.ACEPTADO)
                        .mensaje("Aceptado")
                        .exitoso(true)
                        .debeReintentar(false)
                        .codigoHttp(200)
                        .build());

        when(objectStorageService.subir(any(byte[].class), anyString()))
                .thenAnswer(inv -> (String) inv.getArguments()[1]);

        // Should not throw
        comprobanteHaciendaEnvioService.enviarComprobante("<xml-firmado-de-prueba/>", comprobante);

        ComprobanteElectronico releido = comprobanteElectronicoRepository
                .findById(comprobante.getId())
                .orElseThrow();
        assertThat(releido.getEstado()).isEqualTo("ACEPTADO");
        assertThat(releido.getPdfReferencia()).isEqualTo(expectedPdfRef);
    }
}
