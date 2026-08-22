package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearTiqueteRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de {@link TiqueteService} (Release 2 / Fase C, ver {@code docs/plan-fases-release-2.md}).
 * Mismo patrón de Postgres real (Testcontainers) que {@code NotaCreditoDebitoServiceTest} -- foco
 * en el camino feliz CON y SIN cliente (el hallazgo central de la fase: un Tiquete sin receptor
 * identificado debe poder emitirse), sin la profundidad de reglas de negocio de NC/ND (Tiquete no
 * las tiene).
 */
@Testcontainers
@SpringBootTest
class TiqueteServiceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TiqueteService tiqueteService;

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
    private FacturaMedioPagoRepository facturaMedioPagoRepository;

    @MockitoBean
    private ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;

    @MockitoBean
    private ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    @MockitoBean
    private ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba Tiquete");
        usuario.setEmail("usuario-tiquete-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Tiquete S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Cliente crearCliente() {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente de prueba Tiquete");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return clienteRepository.save(cliente);
    }

    private Producto crearProducto(BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-TIQ-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba Tiquete");
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

    private static LineaFacturaItemRequest lineaSimple(UUID productoId) {
        return new LineaFacturaItemRequest(productoId, BigDecimal.ONE, new BigDecimal("1000.00000"),
                null, null, null, null, null, null, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camino feliz CON cliente
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearTiqueteConClienteAsignaClienteYCalculaTotales() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));

        CrearTiqueteRequest request = new CrearTiqueteRequest(
                cliente.getId(), null, null, null, null, null, null, null,
                List.of(lineaSimple(producto.getId())));

        FacturaResponse response = tiqueteService.crear(request);

        assertThat(response.clienteId()).isEqualTo(cliente.getId());
        assertThat(response.tipoComprobante()).isEqualTo("04");
        assertThat(response.estado()).isEqualTo("GENERADO");
        assertThat(response.subtotal()).isEqualByComparingTo("1000.00000");
        assertThat(response.totalImpuesto()).isEqualByComparingTo("130.00000");
        assertThat(response.total()).isEqualByComparingTo("1130.00000");

        Factura persistida = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(persistida.getClienteId()).isEqualTo(cliente.getId());
        assertThat(persistida.getCondicionVenta()).isEqualTo("01");
        assertThat(persistida.getMoneda()).isEqualTo("CRC");

        List<FacturaMedioPago> mediosPago =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(response.id());
        assertThat(mediosPago).hasSize(1);
        assertThat(mediosPago.get(0).getTotalMedioPago()).isEqualByComparingTo("1130.00000");
    }

    @Test
    void crearTiqueteConClienteInexistenteLanzaClienteNoEncontrado() {
        Producto producto = crearProducto(new BigDecimal("13.00"));

        CrearTiqueteRequest request = new CrearTiqueteRequest(
                UUID.randomUUID(), null, null, null, null, null, null, null,
                List.of(lineaSimple(producto.getId())));

        assertThatThrownBy(() -> tiqueteService.crear(request))
                .isInstanceOf(ClienteNoEncontradoException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camino feliz SIN cliente -- el hallazgo central de Fase C
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearTiqueteSinClienteSePermiteYQuedaConClienteIdNulo() {
        Producto producto = crearProducto(new BigDecimal("13.00"));

        CrearTiqueteRequest request = new CrearTiqueteRequest(
                null, null, null, null, null, null, null, null,
                List.of(lineaSimple(producto.getId())));

        FacturaResponse response = tiqueteService.crear(request);

        assertThat(response.clienteId()).isNull();
        assertThat(response.clienteNombre()).isNull();
        assertThat(response.tipoComprobante()).isEqualTo("04");
        assertThat(response.estado()).isEqualTo("GENERADO");

        Factura persistida = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(persistida.getClienteId()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Triangulación básica de defaults
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearTiqueteConCondicionVentaYMedioPagoExplicitosLosRespeta() {
        Producto producto = crearProducto(new BigDecimal("13.00"));

        CrearTiqueteRequest request = new CrearTiqueteRequest(
                null, "01", null, null, null, "02", "CRC", null,
                List.of(lineaSimple(producto.getId())));

        FacturaResponse response = tiqueteService.crear(request);

        Factura persistida = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(persistida.getMedioPago()).isEqualTo("02");
    }
}
