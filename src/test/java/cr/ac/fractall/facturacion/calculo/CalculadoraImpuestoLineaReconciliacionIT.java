package cr.ac.fractall.facturacion.calculo;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.ExoneracionRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de reconciliación (Release 3 / Fase D, PR2): la suma de
 * {@link CalculadoraImpuestoLinea#calcular} sobre todas las líneas de una factura persistida debe
 * igualar exactamente {@code factura.total_impuesto}. Cada fixture pasa por el camino de
 * PRODUCCIÓN ({@link FacturaService#crear}, nunca filas armadas a mano) para que
 * {@code total_impuesto} sea escrito por {@code LineaFacturaEnsamblador} real -- esto es lo que
 * convierte la reconciliación en un oráculo independiente, no en una tautología (ver el diseño,
 * sección "Reconciliation test mechanics").
 *
 * <p>El mapa de exoneraciones inline se construye con UNA sola llamada batcheada a
 * {@link ImpuestoLineaExoneracionRepository#findByLineaIdIn}, no una consulta por línea -- la
 * misma forma de fetch que usará el futuro {@code ReporteIvaService}.
 */
@Testcontainers
@SpringBootTest
class CalculadoraImpuestoLineaReconciliacionIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private EmpresaRepository empresaRepository;
    @Autowired private ClienteRepository clienteRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private ClienteExoneracionRepository clienteExoneracionRepository;
    @Autowired private ContadorConsecutivoRepository contadorConsecutivoRepository;
    @Autowired private FacturaRepository facturaRepository;
    @Autowired private LineaFacturaRepository lineaFacturaRepository;
    @Autowired private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    @Autowired private FacturaService facturaService;

    private UUID usuarioId;
    private Cliente cliente;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("IT Reconciliacion IVA");
        usuario.setEmail("it-reconciliacion-iva-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);
        usuarioId = usuario.getId();

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa IT Reconciliacion IVA S.A.");
        nueva.setNumeroIdentificacion(
                String.valueOf(100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setCodigoActividad("620200");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("Dirección de prueba IT reconciliación IVA");
        nueva.setEmail("empresa-it-reconciliacion-iva@fractall.test");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        Empresa empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        contadorConsecutivoRepository.save(new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));

        cliente = new Cliente();
        cliente.setNombre("Cliente IT Reconciliacion IVA");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310098" + System.nanoTime() % 1_000_000);
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

    private Producto crearProducto(boolean gravado, BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo("PROD-RECON-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba reconciliación IVA");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(gravado);
        producto.setPorcentajeImpuesto(porcentajeImpuesto);
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        return productoRepository.save(producto);
    }

    private LineaFacturaItemRequest lineaPlana(UUID productoId, BigDecimal precioUnitario) {
        return new LineaFacturaItemRequest(productoId, BigDecimal.ONE, precioUnitario,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Propiedad de reconciliación exacta del diseño: suma de {@code impuestoNeto} sobre todas las
     * líneas, vía UN solo lookup batcheado de exoneraciones inline, comparada con
     * {@code factura.total_impuesto} usando {@code isEqualByComparingTo} (nunca
     * {@code isEqualTo} -- {@code BigDecimal.equals} es sensible a escala y el roundtrip JDBC de
     * {@code numeric(14,5)} no necesariamente conserva la escala en memoria).
     */
    private void asertarReconciliacion(UUID facturaId) {
        List<LineaFactura> lineas = lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId);

        Map<UUID, ImpuestoLineaExoneracion> inlinePorLineaId = impuestoLineaExoneracionRepository
                .findByLineaIdIn(lineas.stream().map(LineaFactura::getId).toList())
                .stream()
                .collect(toMap(ImpuestoLineaExoneracion::getLineaId, identity()));

        BigDecimal suma = lineas.stream()
                .map(l -> CalculadoraImpuestoLinea.calcular(l, inlinePorLineaId.get(l.getId())).impuestoNeto())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalImpuestoAlmacenado = facturaRepository.findById(facturaId).orElseThrow().getTotalImpuesto();
        assertThat(suma).isEqualByComparingTo(totalImpuestoAlmacenado);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F1 — sin exoneración: una línea 13%, una línea gravada al 0%
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void f1SinExoneracionReconciliaConLineasAl13YAl0PorCiento() {
        Producto productoTrece = crearProducto(true, new BigDecimal("13.00"));
        Producto productoCero = crearProducto(true, new BigDecimal("0.00"));

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, null, null, null,
                List.of(
                        lineaPlana(productoTrece.getId(), new BigDecimal("1000.00000")),
                        lineaPlana(productoCero.getId(), new BigDecimal("500.00000"))),
                null, null, null);

        FacturaResponse response = facturaService.crear(request);

        asertarReconciliacion(response.id());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F2 — exoneración legacy vía ClienteExoneracion vigente al 50%
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void f2ExoneracionLegacyViaClienteExoneracionReconcilia() {
        Producto producto = crearProducto(true, new BigDecimal("13.00"));

        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setClienteId(cliente.getId());
        exoneracion.setTipoDocumento("08");
        exoneracion.setNumeroDocumento("EXO-LEGACY-" + UUID.randomUUID().toString().substring(0, 8));
        exoneracion.setNombreInstitucion("Institución de prueba");
        exoneracion.setNumeroArticulo("1");
        exoneracion.setFechaEmision(LocalDateTime.now().minusDays(10));
        exoneracion.setFechaVencimiento(null);
        exoneracion.setPorcentajeExoneracion(new BigDecimal("50.00"));
        exoneracion.setActivo(true);
        exoneracion.setCreateDate(LocalDateTime.now());
        exoneracion.setUpdateDate(LocalDateTime.now());
        exoneracion = clienteExoneracionRepository.save(exoneracion);

        LineaFacturaItemRequest linea = new LineaFacturaItemRequest(
                producto.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                exoneracion.getId(), null, null, null, null, null, null, null);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, null, null, null,
                List.of(linea), null, null, null);

        FacturaResponse response = facturaService.crear(request);

        asertarReconciliacion(response.id());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F3 — exoneración inline: pin de discovery 5 a nivel de DB
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void f3ExoneracionInlineDejaMontoExoneracionAplicadoNuloYReconcilia() {
        Producto productoExonerado = crearProducto(true, new BigDecimal("13.00"));
        Producto productoPlano = crearProducto(true, new BigDecimal("13.00"));

        ExoneracionRequest exoneracionInline = new ExoneracionRequest(
                "08", null, "EXO-INLINE-" + UUID.randomUUID().toString().substring(0, 8),
                null, null, "01", null,
                LocalDate.now().minusDays(5), new BigDecimal("13.00"),
                new BigDecimal("130.00000"));

        LineaFacturaItemRequest lineaConExoneracion = new LineaFacturaItemRequest(
                productoExonerado.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                null, null, null, null, null, null, null, exoneracionInline);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, null, null, null,
                List.of(lineaConExoneracion, lineaPlana(productoPlano.getId(), new BigDecimal("500.00000"))),
                null, null, null);

        FacturaResponse response = facturaService.crear(request);

        List<LineaFactura> lineas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(response.id());
        LineaFactura lineaExonerada = lineas.get(0);

        assertThat(lineaExonerada.getExoneracionId()).isNull();
        assertThat(lineaExonerada.getMontoExoneracionAplicado()).isNull();

        ImpuestoLineaExoneracion inline = impuestoLineaExoneracionRepository
                .findByLineaId(lineaExonerada.getId()).orElseThrow();
        assertThat(inline.getMontoExoneracion()).isEqualByComparingTo(new BigDecimal("130.00000"));

        asertarReconciliacion(response.id());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F4 — línea exenta (producto.gravado = false, porcentaje 0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void f4LineaExentaReconcilia() {
        Producto productoExento = crearProducto(false, BigDecimal.ZERO);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, null, null, null,
                List.of(lineaPlana(productoExento.getId(), new BigDecimal("750.00000"))),
                null, null, null);

        FacturaResponse response = facturaService.crear(request);

        List<LineaFactura> lineas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(response.id());
        assertThat(lineas.get(0).isGravadoAplicado()).isFalse();

        asertarReconciliacion(response.id());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F5 — exoneración inline MAYOR al impuesto de la línea: total negativo, sin piso
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void f5ExoneracionInlineMayorAlImpuestoProduceTotalNegativoYReconcilia() {
        Producto producto = crearProducto(true, new BigDecimal("13.00"));

        ExoneracionRequest exoneracionInline = new ExoneracionRequest(
                "08", null, "EXO-INLINE-MAYOR-" + UUID.randomUUID().toString().substring(0, 8),
                null, null, "01", null,
                LocalDate.now().minusDays(3), new BigDecimal("13.00"),
                new BigDecimal("200.00000"));

        LineaFacturaItemRequest linea = new LineaFacturaItemRequest(
                producto.getId(), BigDecimal.ONE, new BigDecimal("1000.00000"),
                null, null, null, null, null, null, null, exoneracionInline);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, null, null, null,
                List.of(linea), null, null, null);

        FacturaResponse response = facturaService.crear(request);

        Factura facturaGuardada = facturaRepository.findById(response.id()).orElseThrow();
        assertThat(facturaGuardada.getTotalImpuesto()).isNegative();

        asertarReconciliacion(response.id());
    }
}
