package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de {@link ComprobanteXmlPersistenceService} contra Postgres y Vault reales
 * (Testcontainers, mismo bootstrap de AppRole/Transit que {@code FacturaControllerTest}) --
 * {@link XmlFacturaGeneratorService}, {@code XmlFacturaFirmaService} y {@link TransitService}
 * corren de punta a punta, igual que el resto de este proyecto (incluida la firma XAdES-BES real
 * -- ver el javadoc de {@code XmlFacturaFirmaServiceImplTest} para el porqué no se mockea). Solo
 * {@link ObjectStorageService} se reemplaza con {@code @MockitoBean}: Oracle Object Storage no
 * tiene equivalente de contenedor local disponible en este entorno y llamar al OCI real desde una
 * prueba automatizada no es viable (ver el javadoc de {@code OciObjectStorageServiceImpl} para la
 * justificación completa de esta desviación deliberada de la convención "probar contra lo real"
 * que sigue el resto del proyecto).
 *
 * <p>La empresa de prueba carga un certificado {@code .p12} real (vía {@code keytool}, mismo
 * enfoque que {@code EmpresaFlujoFase5Test#generarP12}) a través del camino de producción
 * ({@link EmpresaService#cargarCertificado}) en {@link #setUp()} -- desde que
 * {@code ComprobanteXmlPersistenceService#generarYPersistirXml} firma el XML antes de subirlo,
 * ningún comprobante puede procesarse sin que la empresa tenga certificado cargado.
 */
@Testcontainers
@SpringBootTest
class ComprobanteXmlPersistenceServiceTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-persistencia-1234";

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
     * prueba -- mismo enfoque que {@code EmpresaFlujoFase5Test#generarP12}/
     * {@code XmlFacturaFirmaServiceImplTest#generarP12}, copiado localmente acá.
     */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-persistencia-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-persistencia",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test Persistencia, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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
    private ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;

    @Autowired
    private TransitService transitService;

    @Autowired
    private EmpresaService empresaService;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba persistencia XML");
        usuario.setEmail("usuario-persistencia-xml-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Persistencia XML S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());

        // Certificado .p12 real cargado por el camino de producción -- ver el javadoc de la
        // clase: generarYPersistirXml firma el XML antes de subirlo, así que ningún comprobante
        // puede procesarse sin esto.
        empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ComprobanteElectronico crearComprobanteConFacturaYLinea() {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente de prueba persistencia XML");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        cliente = clienteRepository.save(cliente);

        Producto producto = new Producto();
        producto.setCodigo("PROD-PXML-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba persistencia XML");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
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

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante("01");
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("GENERADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        return comprobanteElectronicoRepository.saveAndFlush(comprobante);
    }

    @Test
    void generarYPersistirXmlSubeUnBlobConElLayoutDocumentadoYPersistaLaReferenciaDevuelta() {
        ComprobanteElectronico comprobante = crearComprobanteConFacturaYLinea();
        String referenciaEsperada = "empresas/" + empresa.getId() + "/comprobantes/"
                + comprobante.getClaveNumerica() + ".xml.enc";

        when(objectStorageService.subir(any(byte[].class), anyString())).thenReturn(referenciaEsperada);

        comprobanteXmlPersistenceService.generarYPersistirXml(comprobante.getId());

        ArgumentCaptor<byte[]> contenidoCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> rutaCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).subir(contenidoCaptor.capture(), rutaCaptor.capture());

        // Ruta del objeto: empresas/{empresaId}/comprobantes/{claveNumerica}.xml.enc -- ver el
        // javadoc de ComprobanteXmlPersistenceService#construirRutaObjeto.
        assertThat(rutaCaptor.getValue()).isEqualTo(referenciaEsperada);

        // Layout exacto documentado en ComprobanteXmlBlobFormat: [4 bytes longitud big-endian]
        // [N bytes DEK envuelta][resto: XML cifrado]. Se decodifica aquí con las mismas
        // primitivas ya probadas por separado (TransitService/EnvelopeCipher) para demostrar que
        // el blob subido es realmente recuperable de punta a punta, sin que
        // ComprobanteXmlPersistenceService necesite exponer un deserializador propio todavía.
        byte[] blob = contenidoCaptor.getValue();
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        int longitudDek = buffer.getInt();
        assertThat(longitudDek).isPositive();

        byte[] dekEnvuelta = new byte[longitudDek];
        buffer.get(dekEnvuelta);
        byte[] xmlCifrado = new byte[buffer.remaining()];
        buffer.get(xmlCifrado);

        byte[] dekPlaintext = transitService.descifrarDek(dekEnvuelta);
        byte[] xmlDescifrado = EnvelopeCipher.descifrar(dekPlaintext, xmlCifrado);
        String xml = new String(xmlDescifrado, StandardCharsets.UTF_8);

        // El XML persistido es el YA FIRMADO -- ver el javadoc de la clase y de
        // ComprobanteXmlPersistenceService#generarYPersistirXml. El prólogo trae
        // "standalone=\"no\"" (a diferencia del XML sin firmar que devuelve
        // XmlFacturaGeneratorServiceImpl) porque XmlFacturaFirmaServiceImpl re-serializa el
        // Document completo vía Transformer -- ver el javadoc de esa clase.
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>");
        assertThat(xml).contains("<Clave>" + comprobante.getClaveNumerica() + "</Clave>");
        assertThat(xml).contains("<ds:Signature");
        assertThat(xml).contains("xades:QualifyingProperties");

        // La referencia devuelta por el mock queda persistida en xml_comprobante_referencia, y
        // el estado avanza a FIRMADO (GENERADO -> FIRMADO -> ENVIADO -> ACEPTADO, ver el javadoc
        // de la clase).
        ComprobanteElectronico releido = comprobanteElectronicoRepository.findById(comprobante.getId()).orElseThrow();
        assertThat(releido.getXmlComprobanteReferencia()).isEqualTo(referenciaEsperada);
        assertThat(releido.getEstado()).isEqualTo("FIRMADO");
    }

    @Test
    void generarYPersistirXmlConComprobanteInexistenteLanzaExcepcionYNuncaLlamaAlAlmacenamiento() {
        UUID comprobanteInexistente = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> comprobanteXmlPersistenceService.generarYPersistirXml(comprobanteInexistente))
                .isInstanceOf(ComprobanteElectronicoNoEncontradoException.class);

        org.mockito.Mockito.verifyNoInteractions(objectStorageService);
    }
}
