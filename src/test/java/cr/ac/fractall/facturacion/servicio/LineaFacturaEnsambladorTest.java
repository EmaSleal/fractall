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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de {@link LineaFacturaEnsamblador} -- extracción de {@code FacturaService.java:342-411}
 * (armado de líneas) y {@code :462-467} (persistencia de hijos de línea), Fase B PR2 (ver diseño
 * D-E). El foco de esta prueba es el guardia de exoneración parametrizado por perfil: los 4 tipos
 * de exoneración exclusivos de Nota de Crédito/Débito ({@code 01/05/06/07}) deben bloquearse SOLO
 * cuando {@code perfil == FACTURA_ELECTRONICA} -- ND necesita justamente esos tipos.
 */
@Testcontainers
@SpringBootTest
class LineaFacturaEnsambladorTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private LineaFacturaEnsamblador lineaFacturaEnsamblador;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private ClienteExoneracionRepository clienteExoneracionRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private LineaFacturaRepository lineaFacturaRepository;

    @Autowired
    private LineaCodigoComercialRepository lineaCodigoComercialRepository;

    @Autowired
    private LineaDescuentoRepository lineaDescuentoRepository;

    @Autowired
    private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba Ensamblador");
        usuario.setEmail("usuario-ensamblador-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Ensamblador S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Cliente crearCliente() {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente de prueba");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return clienteRepository.save(cliente);
    }

    private Producto crearProducto(BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-ENS-" + UUID.randomUUID());
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

    private ClienteExoneracion crearExoneracion(UUID clienteId, String tipoDocumento) {
        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setClienteId(clienteId);
        exoneracion.setTipoDocumento(tipoDocumento);
        exoneracion.setNumeroDocumento("DOC-" + UUID.randomUUID());
        exoneracion.setNombreInstitucion("PROCOMER");
        exoneracion.setNumeroArticulo("1");
        exoneracion.setFechaEmision(LocalDateTime.now().minusDays(1));
        exoneracion.setFechaVencimiento(null);
        exoneracion.setPorcentajeExoneracion(new BigDecimal("100.00"));
        exoneracion.setActivo(true);
        exoneracion.setCreateDate(LocalDateTime.now());
        exoneracion.setUpdateDate(LocalDateTime.now());
        return clienteExoneracionRepository.save(exoneracion);
    }

    private Factura crearFacturaMinima(UUID clienteId) {
        Factura factura = new Factura();
        factura.setClienteId(clienteId);
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(BigDecimal.ZERO);
        factura.setTotalImpuesto(BigDecimal.ZERO);
        factura.setTotal(BigDecimal.ZERO);
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        return facturaRepository.saveAndFlush(factura);
    }

    private LineaFacturaItemRequest itemConExoneracion(UUID productoId, UUID exoneracionId) {
        return new LineaFacturaItemRequest(productoId, BigDecimal.ONE, new BigDecimal("1000.00000"),
                exoneracionId, null, null, null, null, null, null, null);
    }

    @Test
    void ensamblarConExoneracionExclusivaDeNcNdYPerfilFacturaElectronicaLanzaExcepcion() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));
        // '01' es uno de los 4 códigos exclusivos de Nota de Crédito/Débito (sección 4.15.1).
        ClienteExoneracion exoneracionExclusiva = crearExoneracion(cliente.getId(), "01");

        List<LineaFacturaItemRequest> items = List.of(
                itemConExoneracion(producto.getId(), exoneracionExclusiva.getId()));

        assertThatThrownBy(() -> lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA))
                .isInstanceOf(ExoneracionNoAplicableAFacturaElectronicaException.class);
    }

    @Test
    void ensamblarConExoneracionExclusivaDeNcNdYPerfilNotaCreditoAceptaLaExoneracion() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));
        ClienteExoneracion exoneracionExclusiva = crearExoneracion(cliente.getId(), "01");

        List<LineaFacturaItemRequest> items = List.of(
                itemConExoneracion(producto.getId(), exoneracionExclusiva.getId()));

        LineaFacturaEnsamblador.LineasEnsambladas resultado = lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.NOTA_CREDITO);

        assertThat(resultado.lineas()).hasSize(1);
        LineaFactura linea = resultado.lineas().get(0);
        assertThat(linea.getExoneracionId()).isEqualTo(exoneracionExclusiva.getId());
        assertThat(linea.getMontoExoneracionAplicado()).isEqualByComparingTo("130.00000");
    }

    @Test
    void ensamblarConExoneracionExclusivaDeNcNdYPerfilNotaDebitoAceptaLaExoneracion() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));
        ClienteExoneracion exoneracionExclusiva = crearExoneracion(cliente.getId(), "05");

        List<LineaFacturaItemRequest> items = List.of(
                itemConExoneracion(producto.getId(), exoneracionExclusiva.getId()));

        LineaFacturaEnsamblador.LineasEnsambladas resultado = lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.NOTA_DEBITO);

        assertThat(resultado.lineas()).hasSize(1);
        assertThat(resultado.lineas().get(0).getExoneracionId()).isEqualTo(exoneracionExclusiva.getId());
    }

    @Test
    void ensamblarConProductoInexistenteLanzaProductoNoEncontrado() {
        Cliente cliente = crearCliente();
        List<LineaFacturaItemRequest> items = List.of(
                new LineaFacturaItemRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.TEN, null,
                        null, null, null, null, null, null, null));

        assertThatThrownBy(() -> lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void ensamblarConExoneracionInexistenteLanzaClienteExoneracionNoEncontrada() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));
        List<LineaFacturaItemRequest> items = List.of(itemConExoneracion(producto.getId(), UUID.randomUUID()));

        assertThatThrownBy(() -> lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA))
                .isInstanceOf(ClienteExoneracionNoEncontradaException.class);
    }

    @Test
    void ensamblarCalculaSubtotalYTotalImpuestoCorrectamenteSinExoneraciones() {
        Cliente cliente = crearCliente();
        Producto producto1 = crearProducto(new BigDecimal("13.00"));
        Producto producto2 = crearProducto(new BigDecimal("13.00"));

        List<LineaFacturaItemRequest> items = List.of(
                new LineaFacturaItemRequest(producto1.getId(), new BigDecimal("2"), new BigDecimal("1000.00000"),
                        null, null, null, null, null, null, null, null),
                new LineaFacturaItemRequest(producto2.getId(), new BigDecimal("1"), new BigDecimal("2000.00000"),
                        null, null, null, null, null, null, null, null));

        LineaFacturaEnsamblador.LineasEnsambladas resultado = lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA);

        assertThat(resultado.subtotal()).isEqualByComparingTo("4000.00000");
        assertThat(resultado.totalImpuesto()).isEqualByComparingTo("520.00000");
        assertThat(resultado.lineas()).hasSize(2);
        assertThat(resultado.lineas().get(0).getNumeroLinea()).isEqualTo(1);
        assertThat(resultado.lineas().get(1).getNumeroLinea()).isEqualTo(2);
    }

    @Test
    void persistirGuardaLasLineasConElFacturaIdYSusHijos() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto(new BigDecimal("13.00"));
        Factura factura = crearFacturaMinima(cliente.getId());

        List<LineaFacturaItemRequest> items = List.of(
                new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                        null, null, null, null, null, null, null, null));

        LineaFacturaEnsamblador.LineasEnsambladas ensambladas = lineaFacturaEnsamblador.ensamblar(
                items, cliente, TipoComprobantePerfil.FACTURA_ELECTRONICA);

        lineaFacturaEnsamblador.persistir(factura.getId(), ensambladas);

        List<LineaFactura> persistidas = lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(factura.getId());
        assertThat(persistidas).hasSize(1);
        assertThat(persistidas.get(0).getFacturaId()).isEqualTo(factura.getId());
        assertThat(persistidas.get(0).getProductoId()).isEqualTo(producto.getId());
    }
}
