package cr.ac.fractall.facturacion.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
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
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContext;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración a nivel HTTP de {@code FacturaController} (Fase 7, secciones 4.9 y
 * 4.12-4.15 de {@code arquitectura-facturacion-electronica-cr.md}) -- necesaria porque
 * {@code FacturaServiceTest} solo ejercita la capa de servicio; sin esta prueba se repetiría el
 * mismo hueco de cobertura que se corrigió en revisiones de código previas ("solo probado a
 * nivel de servicio, nunca a través de la pila HTTP real").
 *
 * <p>Mismo bootstrap de Postgres + Vault vía Testcontainers que {@code CatalogoControllerTest} --
 * a diferencia de esa clase, {@code FacturaController} SÍ toca Vault de verdad desde la Fase 8:
 * {@code ComprobanteXmlPersistenceService#generarYPersistirXml} firma el XML (certificado
 * descifrado desde Postgres + PIN leído de Vault KV, ver {@code XmlFacturaFirmaServiceImpl}) antes
 * de subirlo -- por eso {@link #crearContextoCompleto()} carga un certificado {@code .p12} real
 * (vía {@code keytool}, mismo enfoque que {@code EmpresaFlujoFase5Test#generarP12}) a través del
 * camino de producción ({@link EmpresaService#cargarCertificado}).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class FacturaControllerTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-factura-controller-1234";

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
     * prueba -- mismo enfoque que {@code EmpresaFlujoFase5Test#generarP12}, copiado localmente acá.
     */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-facturacontroller-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test-facturacontroller",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test FacturaController, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
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

    // Jackson 3 (tools.jackson.databind) -- ver el comentario de CatalogoControllerTest sobre
    // por qué no es com.fasterxml.jackson.databind aquí.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private ContadorConsecutivoRepository contadorConsecutivoRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private JwtService jwtService;

    // Fase 8: reemplaza la implementación real OCI-backed -- ver el javadoc de
    // OciObjectStorageServiceImpl sobre por qué (Instance Principal no funciona fuera de una VM
    // de OCI real, y no hay equivalente de contenedor local disponible en este entorno).
    @MockitoBean
    private ObjectStorageService objectStorageService;

    // Fase 8 (envío a Hacienda): ComprobanteXmlPersistenceService#generarYPersistirXml ahora
    // invoca ComprobanteHaciendaEnvioService justo después de FIRMADO -- se mockea para evitar una
    // llamada HTTP real a Hacienda desde esta prueba de integración, mismo motivo que
    // CatalogoControllerTest mockea HaciendaApiService.
    @MockitoBean
    private HaciendaComprobanteApiService haciendaComprobanteApiService;

    private record ContextoDePrueba(String accessToken, UUID empresaId, UUID clienteId, UUID productoId) {
    }

    private ContextoDePrueba crearContextoCompleto() {
        LocalDateTime ahora = LocalDateTime.now();

        return TenantContextDescartable.ejecutar(() -> {
            Usuario usuario = new Usuario();
            usuario.setNombre("Persona de prueba FacturaController");
            usuario.setEmail("factura-controller-" + UUID.randomUUID() + "@fractall.test");
            usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa de Prueba FacturaController S.A.");
            empresa.setNumeroIdentificacion(String.valueOf(
                    200_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 700_000_000_000L)));
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuario.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            CredencialHacienda credencial = new CredencialHacienda();
            credencial.setEmpresaId(empresa.getId());
            credencial.setAmbiente("SANDBOX");
            credencial.setUsuarioHacienda("usuario-" + empresa.getId() + "@hacienda.test");
            credencial.setCredencialReferencia(
                    "secret/data/empresas/" + empresa.getId() + "/hacienda/sandbox/password");
            credencial.setConfiguradaEn(ahora);
            credencial.setConfiguradaPor(usuario.getId());
            credencialHaciendaRepository.save(credencial);

            // Respuesta genérica de "recibido, en procesamiento" -- suficiente para que
            // ComprobanteHaciendaEnvioService no lance CredencialHaciendaNoEncontradaException ni
            // intente ninguna llamada de red real; las reglas de transición de estado en sí se
            // prueban por separado en ComprobanteHaciendaEnvioServiceTest.
            when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any())).thenReturn(
                    RespuestaHaciendaDTO.builder()
                            .fechaRespuesta(ahora)
                            .codigoMensaje(MensajeHacienda.PROCESANDO)
                            .mensaje("Comprobante recibido, en procesamiento")
                            .exitoso(false)
                            .debeReintentar(true)
                            .codigoHttp(202)
                            .build());

            String accessToken = jwtService.generarToken(usuario.getId(), empresa.getId());

            TenantContext.set(empresa.getId());
            try {
                // Certificado .p12 real cargado por el camino de producción -- ver el javadoc de
                // la clase: generarYPersistirXml firma el XML antes de subirlo.
                empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO);

                contadorConsecutivoRepository.save(new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));

                Cliente cliente = new Cliente();
                cliente.setNombre("Cliente HTTP Factura");
                cliente.setTipoIdentificacion("02");
                cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
                cliente.setRequiereFacturaElectronica(true);
                cliente.setCreateDate(ahora);
                cliente.setUpdateDate(ahora);
                cliente = clienteRepository.save(cliente);

                Producto producto = new Producto();
                producto.setCodigo("PROD-HF-" + UUID.randomUUID());
                producto.setDescripcion("Producto de prueba FacturaController");
                producto.setCodigoCabys("2132100000100");
                producto.setDescripcionCabys("Descripción CABYS de prueba");
                producto.setCabysValidadoEn(ahora);
                producto.setCodigoUnidadFe("Unid");
                producto.setPrecioVenta(new BigDecimal("1500.00000"));
                producto.setGravado(true);
                producto.setPorcentajeImpuesto(new BigDecimal("13.00"));
                producto.setActivo(true);
                producto.setCreateDate(ahora);
                producto.setUpdateDate(ahora);
                producto = productoRepository.save(producto);

                return new ContextoDePrueba(accessToken, empresa.getId(), cliente.getId(), producto.getId());
            } finally {
                TenantContext.clear();
            }
        });
    }

    @Test
    void postFacturaConLineaSinExoneracionRetorna201ConComprobanteGenerado() throws Exception {
        ContextoDePrueba contexto = crearContextoCompleto();

        CrearFacturaRequest request = new CrearFacturaRequest(
                contexto.clienteId(), null, null, null, null, null,
                java.util.List.of(new LineaFacturaItemRequest(
                        contexto.productoId(), new BigDecimal("3"), new BigDecimal("1500.00000"), null)));

        mockMvc.perform(post("/facturas")
                        .header("Authorization", "Bearer " + contexto.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("GENERADO"))
                .andExpect(jsonPath("$.tipoComprobante").value("01"))
                .andExpect(jsonPath("$.ambienteHacienda").value("SANDBOX"))
                .andExpect(jsonPath("$.claveNumerica").value(org.hamcrest.Matchers.hasLength(50)))
                .andExpect(jsonPath("$.claveNumerica").value(org.hamcrest.Matchers.startsWith("506")))
                .andExpect(jsonPath("$.consecutivo").value(org.hamcrest.Matchers.hasLength(20)))
                .andExpect(jsonPath("$.subtotal").value(4500.0))
                .andExpect(jsonPath("$.lineas[0].exoneracionId").doesNotExist());
    }

    /**
     * Prueba de cableado de punta a punta (Fase 8): confirma que
     * {@code FacturaController#crear} invoca {@code ComprobanteXmlPersistenceService} DESPUÉS de
     * que {@code FacturaService#crear} ya hizo commit -- ver el javadoc de ambas clases. Solo
     * {@link ObjectStorageService} está mockeado; la generación del XML y el cifrado corren de
     * punta a punta contra Postgres/Vault reales.
     */
    @Test
    void postFacturaPersisteLaReferenciaDelXmlDevueltaPorElAlmacenamiento() throws Exception {
        ContextoDePrueba contexto = crearContextoCompleto();
        String referenciaEsperada = "empresas/" + contexto.empresaId() + "/comprobantes/referencia-de-prueba.xml.enc";
        when(objectStorageService.subir(any(byte[].class), anyString())).thenReturn(referenciaEsperada);

        CrearFacturaRequest request = new CrearFacturaRequest(
                contexto.clienteId(), null, null, null, null, null,
                java.util.List.of(new LineaFacturaItemRequest(
                        contexto.productoId(), new BigDecimal("2"), new BigDecimal("1000.00000"), null)));

        String cuerpoRespuesta = mockMvc.perform(post("/facturas")
                        .header("Authorization", "Bearer " + contexto.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID comprobanteId = UUID.fromString(objectMapper.readTree(cuerpoRespuesta).get("comprobanteId").asText());

        TenantContext.set(contexto.empresaId());
        try {
            ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId).orElseThrow();
            assertThat(comprobante.getXmlComprobanteReferencia()).isEqualTo(referenciaEsperada);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Cara "A" del hallazgo de revisión sobre {@code CredencialHaciendaNoEncontradaException}:
     * la excepción debe mapearse a 503, mismo criterio que
     * {@code ContadorConsecutivoNoEncontradoException} -- nunca un 500 crudo, aunque la factura y
     * el comprobante ya hayan quedado persistidos en {@code FIRMADO} (ver el javadoc de
     * {@code ComprobanteXmlPersistenceService} sobre por qué ese estado parcial es un riesgo
     * aceptado, no algo que este endpoint intente revertir).
     */
    @Test
    void postFacturaSinCredencialHaciendaRetorna503() throws Exception {
        ContextoDePrueba contexto = crearContextoCompleto();
        // CredencialHacienda no extiende TenantAwareEntity (ver su modelo), pero el resolutor de
        // tenant de Hibernate falla de forma cerrada para CUALQUIER entidad al abrir el
        // EntityManager -- mismo motivo documentado en TenantContextDescartable.
        TenantContextDescartable.ejecutar(() -> credencialHaciendaRepository
                .findByEmpresaIdAndAmbiente(contexto.empresaId(), "SANDBOX")
                .ifPresent(credencialHaciendaRepository::delete));

        CrearFacturaRequest request = new CrearFacturaRequest(
                contexto.clienteId(), null, null, null, null, null,
                java.util.List.of(new LineaFacturaItemRequest(
                        contexto.productoId(), BigDecimal.ONE, new BigDecimal("1000.00000"), null)));

        mockMvc.perform(post("/facturas")
                        .header("Authorization", "Bearer " + contexto.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void postFacturaConClienteDeOtroTenantRetorna404() throws Exception {
        ContextoDePrueba contextoA = crearContextoCompleto();
        ContextoDePrueba contextoB = crearContextoCompleto();

        CrearFacturaRequest request = new CrearFacturaRequest(
                contextoA.clienteId(), null, null, null, null, null,
                java.util.List.of(new LineaFacturaItemRequest(
                        contextoA.productoId(), BigDecimal.ONE, BigDecimal.TEN, null)));

        // El cliente y producto pertenecen al tenant A, pero se solicita con el token del tenant B.
        mockMvc.perform(post("/facturas")
                        .header("Authorization", "Bearer " + contextoB.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
