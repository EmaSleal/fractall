package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaNotaCreditoRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de {@link NotaCreditoDebitoService} (Release 2 / Fase B, PR3, ver diseño D-E). Usa
 * Postgres real (Testcontainers) para que los triggers de V18 (regla 3/7, defensa en profundidad)
 * estén presentes, aunque el foco de esta prueba es que el pre-chequeo de Java rechace ANTES de
 * llegar a ellos en el camino normal -- mismo patrón que {@code ComprobanteEmisionServiceTest}/
 * {@code LineaFacturaEnsambladorTest}. Solo se mockean las hojas que tocan red
 * ({@code ComprobanteXmlCifradoDescargador}/{@code ComprobanteHaciendaEnvioService}/{@code
 * ComprobanteXmlPersistenceService}, dependencias transitivas de {@code ComprobanteEmisionService}).
 */
@Testcontainers
@SpringBootTest
class NotaCreditoDebitoServiceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private NotaCreditoDebitoService notaCreditoDebitoService;

    @Autowired
    private ComprobanteEmisionService comprobanteEmisionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private LineaDescuentoRepository lineaDescuentoRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;

    @MockitoBean
    private ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;

    @MockitoBean
    private ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    @MockitoBean
    private ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;

    private UUID usuarioId;
    private Empresa empresa;
    private Cliente cliente;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba NotaCreditoDebito");
        usuario.setEmail("usuario-nc-nd-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa NC/ND S.A.");
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

        cliente = new Cliente();
        cliente.setNombre("Cliente de prueba NC/ND");
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
        producto.setCodigo("PROD-NC-ND-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba");
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

    /** Fixture: factura origen + su única línea + su comprobante_electronico. */
    private record FacturaOrigenFixture(Factura factura, LineaFactura linea, ComprobanteElectronico comprobante) {}

    /**
     * Crea una factura origen tipo 01 con una única línea (armada directamente, sin pasar por
     * {@code LineaFacturaEnsamblador}, para control total de subtotal/impuesto en la prueba), y
     * su {@code ComprobanteElectronico} en el estado indicado -- {@code ACEPTADO} en el camino
     * feliz, otro valor para probar la regla 1.
     */
    private FacturaOrigenFixture crearFacturaOrigenConLinea(
            BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal porcentajeImpuesto,
            BigDecimal montoDescuento, String estado) {
        BigDecimal montoTotalLinea = cantidad.multiply(precioUnitario).setScale(5, RoundingMode.HALF_UP);
        BigDecimal descuento = montoDescuento != null ? montoDescuento : BigDecimal.ZERO;
        BigDecimal subtotalLinea = montoTotalLinea.subtract(descuento).setScale(5, RoundingMode.HALF_UP);
        BigDecimal impuestoLinea = subtotalLinea.multiply(porcentajeImpuesto)
                .divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP);
        BigDecimal totalFactura = subtotalLinea.add(impuestoLinea).setScale(5, RoundingMode.HALF_UP);

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

        if (montoDescuento != null) {
            LineaDescuento descuentoEntidad = new LineaDescuento();
            descuentoEntidad.setLineaId(linea.getId());
            descuentoEntidad.setOrden((short) 1);
            descuentoEntidad.setMontoDescuento(montoDescuento);
            descuentoEntidad.setCodigoDescuento("01");
            lineaDescuentoRepository.save(descuentoEntidad);
        }

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
    // Nota de Crédito -- orden de validación 7 → 1 → 6 → 2 → 3-por-línea → 3-saldo (tasks 3.3/3.4)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearNotaCreditoConOrigenNoAceptadoLanzaFacturaOrigenNoAceptada() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "GENERADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección de monto",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));

        long facturasAntes = facturaRepository.count();

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(FacturaOrigenNoAceptadaException.class);

        assertThat(facturaRepository.count()).isEqualTo(facturasAntes);
    }

    @Test
    void crearNotaCreditoConOrigenTipoNoFacturaElectronicaLanzaReferenciaNoEsFacturaElectronica() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");
        // Simula que el origen es en realidad otra NC/ND (tipo_comprobante='03') -- ver regla 7.
        origen.comprobante().setTipoComprobante("03");
        comprobanteElectronicoRepository.save(origen.comprobante());

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección de monto",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(ReferenciaNoEsFacturaElectronicaException.class);
    }

    @Test
    void crearNotaCreditoConLineaQueNoPerteneceAFacturaOrigenLanzaLineaOrigenNoPerteneceAFactura() {
        FacturaOrigenFixture origenA = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");
        FacturaOrigenFixture origenB = crearFacturaOrigenConLinea(
                BigDecimal.ONE, new BigDecimal("500.00000"), new BigDecimal("13.00"), null, "ACEPTADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origenA.factura().getId(), "02", null, "Corrección de monto",
                List.of(new LineaNotaCreditoRequest(origenB.linea().getId(), BigDecimal.ONE)));

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(LineaOrigenNoPerteneceAFacturaException.class);
    }

    @Test
    void crearNotaCreditoConCantidadMayorAOrigenLanzaCantidadAcreditadaExcedeOrigen() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección de monto",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), new BigDecimal("11"))));

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(request))
                .isInstanceOf(CantidadAcreditadaExcedeOrigenException.class);
    }

    @Test
    void crearNotaCreditoConMontoQueExcedeElSaldoLanzaMontoNotaCreditoExcedeOrigenAntesDeEscribir() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.ONE, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");

        CrearNotaCreditoRequest primera = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Primera NC (consume todo el saldo)",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));
        notaCreditoDebitoService.crearNotaCredito(primera);

        CrearNotaCreditoRequest segunda = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Segunda NC (saldo ya agotado)",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), BigDecimal.ONE)));

        long facturasAntes = facturaRepository.count();

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaCredito(segunda))
                .isInstanceOf(MontoNotaCreditoExcedeOrigenException.class);

        assertThat(facturaRepository.count()).isEqualTo(facturasAntes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nota de Crédito -- camino feliz: herencia de cliente (regla 6), totales, prorrateo (task 3.5)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearNotaCreditoParcialDentroDelSaldoHeredaClienteYCalculaTotalesCorrectamente() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");

        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección parcial de monto",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), new BigDecimal("4"))));

        FacturaResponse response = notaCreditoDebitoService.crearNotaCredito(request);

        assertThat(response.clienteId()).isEqualTo(cliente.getId());
        assertThat(response.tipoComprobante()).isEqualTo("03");
        assertThat(response.estado()).isEqualTo("GENERADO");
        // factor = 4/10; origen subtotalLinea = 10000.00000 -> NC subtotal = 4000.00000
        assertThat(response.subtotal()).isEqualByComparingTo("4000.00000");
        assertThat(response.totalImpuesto()).isEqualByComparingTo("520.00000");
        assertThat(response.total()).isEqualByComparingTo("4520.00000");
        assertThat(response.lineas()).hasSize(1);
        assertThat(response.lineas().get(0).cantidad()).isEqualByComparingTo("4");

        Factura persistida = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(persistida.getFacturaReferenciaId()).isEqualTo(origen.factura().getId());
        assertThat(persistida.getMoneda()).isEqualTo(origen.factura().getMoneda());
        assertThat(persistida.getCondicionVenta()).isEqualTo(origen.factura().getCondicionVenta());
    }

    @Test
    void crearNotaCreditoProrateaSubtotalYDescuentoRespetandoElInvarianteMontoTotalMenosDescuentosIgualSubtotal() {
        // origen: cantidad=10, precio=1000 -> montoTotal=10000; descuento=1000 -> subtotalLinea=9000
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.TEN, new BigDecimal("1000.00000"), new BigDecimal("13.00"),
                new BigDecimal("1000.00000"), "ACEPTADO");

        // NC cantidad=5 (mitad) -> factor=0.5
        CrearNotaCreditoRequest request = new CrearNotaCreditoRequest(
                origen.factura().getId(), "02", null, "Corrección parcial con descuento",
                List.of(new LineaNotaCreditoRequest(origen.linea().getId(), new BigDecimal("5"))));

        FacturaResponse response = notaCreditoDebitoService.crearNotaCredito(request);

        assertThat(response.lineas()).hasSize(1);
        LineaFacturaResponse lineaNc = response.lineas().get(0);
        // subtotal prorateado: 9000 * 0.5 = 4500.00000
        assertThat(lineaNc.subtotal()).isEqualByComparingTo("4500.00000");
        assertThat(lineaNc.descuentos()).hasSize(1);
        // descuento prorateado: 1000 * 0.5 = 500.00000
        assertThat(lineaNc.descuentos().get(0).montoDescuento()).isEqualByComparingTo("500.00000");

        BigDecimal montoTotalNc = new BigDecimal("1000.00000").multiply(new BigDecimal("5"));
        BigDecimal descuentoProrateado = lineaNc.descuentos().get(0).montoDescuento();
        assertThat(montoTotalNc.subtract(descuentoProrateado)).isEqualByComparingTo(lineaNc.subtotal());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nota de Débito -- catálogo (task 3.6), mismas reglas 7/1, herencia de cliente y referencia
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void crearNotaDebitoConOrigenNoAceptadoLanzaFacturaOrigenNoAceptada() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.ONE, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "GENERADO");
        Producto productoNd = crearProducto(new BigDecimal("13.00"));

        CrearNotaDebitoRequest request = new CrearNotaDebitoRequest(
                origen.factura().getId(), "01", null, "Cargo olvidado",
                List.of(new LineaFacturaItemRequest(productoNd.getId(), BigDecimal.ONE, new BigDecimal("500.00000"),
                        null, null, null, null, null, null, null, null)));

        assertThatThrownBy(() -> notaCreditoDebitoService.crearNotaDebito(request))
                .isInstanceOf(FacturaOrigenNoAceptadaException.class);
    }

    @Test
    void crearNotaDebitoConCatalogoHeredaClienteYDerivaReferenciaDelOrigen() {
        FacturaOrigenFixture origen = crearFacturaOrigenConLinea(
                BigDecimal.ONE, new BigDecimal("1000.00000"), new BigDecimal("13.00"), null, "ACEPTADO");
        Producto productoNd = crearProducto(new BigDecimal("13.00"));

        CrearNotaDebitoRequest request = new CrearNotaDebitoRequest(
                origen.factura().getId(), "01", null, "Cargo olvidado",
                List.of(new LineaFacturaItemRequest(productoNd.getId(), BigDecimal.ONE, new BigDecimal("500.00000"),
                        null, null, null, null, null, null, null, null)));

        FacturaResponse response = notaCreditoDebitoService.crearNotaDebito(request);

        assertThat(response.clienteId()).isEqualTo(cliente.getId());
        assertThat(response.tipoComprobante()).isEqualTo("02");
        assertThat(response.lineas()).hasSize(1);
        assertThat(response.subtotal()).isEqualByComparingTo("500.00000");

        List<FacturaInformacionReferencia> referencias =
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(response.id());
        assertThat(referencias).hasSize(1);
        assertThat(referencias.get(0).getTipoDocIr()).isEqualTo("01");
        assertThat(referencias.get(0).getNumero()).isEqualTo(origen.comprobante().getClaveNumerica());
        assertThat(referencias.get(0).getCodigo()).isEqualTo("01");
    }
}
