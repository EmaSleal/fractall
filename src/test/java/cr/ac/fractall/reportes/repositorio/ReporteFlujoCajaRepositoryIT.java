package cr.ac.fractall.reportes.repositorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.CobroFactura;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.CobroFacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración de {@link ReporteFlujoCajaRepository} -- Q1 ({@code buscarVentasEnPeriodo})
 * y Q2 ({@code buscarCobrosEnPeriodo}) (Release 3 / Fase D, PR2 de {@code reporte-flujo-caja}, ver
 * el diseño obs #918). Q3 (cartera) llega en la PR3 de este mismo cambio -- ver
 * {@code sdd/reporte-flujo-caja/tasks}, Fase 2 vs. Fase 3.
 *
 * <p>Fixtures construidas por saves directos de repositorio (no vía {@code FacturaService}/
 * {@code CobroFacturaService}), mismo estilo que {@code V24CorregirAlcanceCobroFacturaIT}: ninguna
 * de estas pruebas necesita Vault/XML/Hacienda ni la validación HTTP de esos servicios, solo un
 * {@code factura}/{@code comprobante_electronico}/{@code cobro_factura} reales en Postgres bajo el
 * {@code empresa_id} correcto (poblado automáticamente por {@code @TenantId} de Hibernate al
 * momento del {@code save}, según el {@link TenantContext} activo).
 *
 * <p>Las consultas bajo prueba filtran {@code empresa_id} EXPLÍCITAMENTE por parámetro (el
 * {@code @TenantId} de Hibernate no aplica a SQL nativo -- ver el javadoc de
 * {@link ReporteFlujoCajaRepository}), así que cada prueba de aislamiento arma una fila real bajo
 * OTRO tenant y confirma que el parámetro explícito, no el contexto de sesión, es lo que la
 * excluye.
 */
@Testcontainers
@SpringBootTest
class ReporteFlujoCajaRepositoryIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private CobroFacturaRepository cobroFacturaRepository;

    @Autowired
    private ReporteFlujoCajaRepository reporteFlujoCajaRepository;

    /** Período fijo de agosto 2026 (media-noche inclusiva / media-noche exclusiva), usado por todas las pruebas. */
    private static final LocalDateTime DESDE_AGOSTO = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime HASTA_AGOSTO_EXCLUSIVO = LocalDateTime.of(2026, 9, 1, 0, 0);

    private Empresa empresaA;
    private Usuario usuarioA;

    @BeforeEach
    void setUp() {
        // Ver V24CorregirAlcanceCobroFacturaIT: hace falta un empresa_id resuelto en contexto para
        // abrir cualquier EntityManager de este SessionFactory, aunque Usuario/Empresa no sean
        // tenant-aware.
        TenantContext.set(UUID.randomUUID());

        usuarioA = usuarioRepository.save(nuevoUsuario("A"));
        empresaA = empresaRepository.save(nuevaEmpresa("Empresa Flujo Caja A S.A.", usuarioA.getId()));

        TenantContext.set(empresaA.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Usuario nuevoUsuario(String sufijo) {
        LocalDateTime ahora = LocalDateTime.now();
        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba flujo de caja " + sufijo);
        usuario.setEmail("usuario-flujo-caja-" + sufijo + "-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(ahora);
        usuario.setUpdateDate(ahora);
        return usuario;
    }

    private static Empresa nuevaEmpresa(String razonSocial, UUID creadoPor) {
        LocalDateTime ahora = LocalDateTime.now();
        Empresa empresa = new Empresa();
        empresa.setRazonSocial(razonSocial);
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(creadoPor);
        empresa.setCreateDate(ahora);
        empresa.setUpdateDate(ahora);
        return empresa;
    }

    private static Cliente nuevoCliente(String nombre, String numeroIdentificacion) {
        LocalDateTime ahora = LocalDateTime.now();
        Cliente cliente = new Cliente();
        cliente.setNombre(nombre);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion(numeroIdentificacion);
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(ahora);
        cliente.setUpdateDate(ahora);
        return cliente;
    }

    /** Ver AislamientoMultiTenantTest/V24CorregirAlcanceCobroFacturaIT: el CHECK de V4 exige plazo_credito para '02'. */
    private static Factura nuevaFactura(UUID clienteId, UUID creadoPor, String condicionVenta, String total) {
        LocalDateTime ahora = LocalDateTime.now();
        Factura factura = new Factura();
        factura.setClienteId(clienteId);
        factura.setCondicionVenta(condicionVenta);
        if ("02".equals(condicionVenta)) {
            factura.setPlazoCredito(30);
        }
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(new BigDecimal(total));
        factura.setTotalImpuesto(BigDecimal.ZERO);
        factura.setTotal(new BigDecimal(total));
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(creadoPor);
        factura.setCreateDate(ahora);
        factura.setUpdateDate(ahora);
        return factura;
    }

    private static ComprobanteElectronico nuevoComprobante(
            UUID facturaId, String tipoComprobante, String consecutivo, String claveNumerica,
            LocalDateTime fechaEmision) {
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(facturaId);
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante(tipoComprobante);
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("ACEPTADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(fechaEmision);
        comprobante.setIntentosConsulta(0);
        return comprobante;
    }

    private static CobroFactura nuevoCobro(
            UUID facturaId, String monto, String medioPago, LocalDateTime fechaCobro, UUID registradoPor) {
        CobroFactura cobro = new CobroFactura();
        cobro.setFacturaId(facturaId);
        cobro.setMontoCobrado(new BigDecimal(monto));
        cobro.setFechaCobro(fechaCobro);
        cobro.setMedioPago(medioPago);
        cobro.setReferencia(null);
        cobro.setRegistradoPor(registradoPor);
        cobro.setCreateDate(LocalDateTime.now());
        return cobro;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aislamiento por tenant (Requisito "Tenant Isolation on All Three Native Queries")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prueba real del filtro explícito {@code empresa_id} de Q1 -- una factura ACEPTADA de OTRO
     * tenant, dentro del período consultado, nunca debe aparecer en la serie de ventas del tenant
     * actual, aunque ambas facturas compartan literalmente la misma tabla física.
     */
    @Test
    void ventasDeOtroTenantNoAparecenEnLaSerieDeVentas() {
        Cliente clienteA = clienteRepository.save(nuevoCliente("Cliente Flujo Caja A", "800000001"));
        Factura facturaA = facturaRepository.save(
                nuevaFactura(clienteA.getId(), usuarioA.getId(), "01", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaA.getId(), "01", "vfc-a-001", "clave-vfc-a-001",
                LocalDateTime.of(2026, 8, 10, 9, 0)));

        // Fila real de OTRO tenant, misma tabla física, dentro del mismo período.
        Usuario usuarioB = usuarioRepository.save(nuevoUsuario("B"));
        Empresa empresaB = empresaRepository.save(nuevaEmpresa("Empresa Flujo Caja B S.A.", usuarioB.getId()));
        TenantContext.set(empresaB.getId());
        Cliente clienteB = clienteRepository.save(nuevoCliente("Cliente Flujo Caja B", "800000002"));
        Factura facturaB = facturaRepository.save(
                nuevaFactura(clienteB.getId(), usuarioB.getId(), "01", "9999.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaB.getId(), "01", "vfc-b-001", "clave-vfc-b-001",
                LocalDateTime.of(2026, 8, 10, 9, 0)));

        // Vuelve al contexto del tenant A para consultar -- el filtro que cuenta es el parámetro
        // explícito de la consulta, no el TenantContext de la sesión (SQL nativo, ver el javadoc
        // de ReporteFlujoCajaRepository).
        TenantContext.set(empresaA.getId());
        List<Object[]> filas = reporteFlujoCajaRepository.buscarVentasEnPeriodo(
                empresaA.getId(), DESDE_AGOSTO, HASTA_AGOSTO_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        assertThat((UUID) filas.get(0)[0]).isEqualTo(facturaA.getId());
    }

    /**
     * Prueba real del filtro explícito {@code empresa_id} de Q2 -- un cobro real de OTRO tenant,
     * dentro del período consultado, nunca debe aparecer en la serie de cobros del tenant actual.
     */
    @Test
    void cobrosDeOtroTenantNoAparecenEnLaSerieDeCobros() {
        Cliente clienteA = clienteRepository.save(nuevoCliente("Cliente Flujo Caja A Cobro", "800000003"));
        Factura facturaA = facturaRepository.save(
                nuevaFactura(clienteA.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaA.getId(), "01", "cfc-a-001", "clave-cfc-a-001",
                LocalDateTime.of(2026, 8, 5, 9, 0)));
        cobroFacturaRepository.saveAndFlush(nuevoCobro(
                facturaA.getId(), "500.00000", "04", LocalDateTime.of(2026, 8, 20, 10, 0), usuarioA.getId()));

        // Cobro real de OTRO tenant, dentro del mismo período.
        Usuario usuarioB = usuarioRepository.save(nuevoUsuario("C"));
        Empresa empresaB = empresaRepository.save(nuevaEmpresa("Empresa Flujo Caja C S.A.", usuarioB.getId()));
        TenantContext.set(empresaB.getId());
        Cliente clienteB = clienteRepository.save(nuevoCliente("Cliente Flujo Caja C", "800000004"));
        Factura facturaB = facturaRepository.save(
                nuevaFactura(clienteB.getId(), usuarioB.getId(), "02", "2000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaB.getId(), "01", "cfc-b-001", "clave-cfc-b-001",
                LocalDateTime.of(2026, 8, 5, 9, 0)));
        cobroFacturaRepository.saveAndFlush(nuevoCobro(
                facturaB.getId(), "999.00000", "04", LocalDateTime.of(2026, 8, 20, 10, 0), usuarioB.getId()));

        TenantContext.set(empresaA.getId());
        List<Object[]> filas = reporteFlujoCajaRepository.buscarCobrosEnPeriodo(
                empresaA.getId(), DESDE_AGOSTO, HASTA_AGOSTO_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        assertThat((UUID) filas.get(0)[5]).isEqualTo(facturaA.getId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D2 -- contado incluido en ventas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Requisito "Ventas Series Includes All condicion_venta Values" (D2): una factura
     * {@code condicion_venta='01'} (contado) debe aparecer en la serie de ventas -- Q1
     * deliberadamente NO aplica la restricción de elegibilidad de cobro
     * {@code IN ('02','03','04')} que sí usa {@code CobroFacturaService}.
     */
    @Test
    void ventasIncluyenCondicionVentaContado() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Contado", "800000005"));
        Factura facturaContado = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "01", "1130.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaContado.getId(), "01", "contado-001", "clave-contado-001",
                LocalDateTime.of(2026, 8, 3, 8, 0)));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarVentasEnPeriodo(
                empresaA.getId(), DESDE_AGOSTO, HASTA_AGOSTO_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        Object[] fila = filas.get(0);
        assertThat((UUID) fila[0]).isEqualTo(facturaContado.getId());
        assertThat((String) fila[4]).as("condicion_venta").isEqualTo("01");
        assertThat((BigDecimal) fila[8]).as("total").isEqualByComparingTo("1130.00000");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Finding 4 -- NC dentro de su propio período (fila cruda, sin signo)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finding 4 del diseño: una Nota de Crédito ACEPTADA, con {@code condicion_venta} heredada de
     * su factura origen y {@code fecha_emision} PROPIA dentro del período consultado, debe
     * aparecer como su PROPIA fila en la serie de ventas -- Q1 no filtra por {@code
     * tipo_comprobante}. Esta prueba fija el conjunto de filas SIN signar (el signo(), Decisión
     * B5, se aplica en la PR4 de este cambio, no en el repositorio): la fila de la NC trae su
     * {@code total} crudo (positivo), no negativo -- pin explícito de que este PR solo entrega
     * datos crudos, nunca aritmética de signo.
     */
    @Test
    void notaCreditoAceptadaRestaDeLaSerieDeVentasEnSuPropioPeriodo() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente NC Propio Periodo", "800000006"));

        Factura origen = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                origen.getId(), "01", "nc-origen-001", "clave-nc-origen-001",
                LocalDateTime.of(2026, 8, 4, 9, 0)));

        Factura notaCredito = nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "300.00000");
        notaCredito.setFacturaReferenciaId(origen.getId());
        notaCredito = facturaRepository.saveAndFlush(notaCredito);
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                notaCredito.getId(), "03", "nc-001", "clave-nc-001",
                LocalDateTime.of(2026, 8, 18, 11, 0)));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarVentasEnPeriodo(
                empresaA.getId(), DESDE_AGOSTO, HASTA_AGOSTO_EXCLUSIVO);

        assertThat(filas)
                .extracting(fila -> tuple(fila[0], fila[1], fila[8]))
                .containsExactlyInAnyOrder(
                        tuple(origen.getId(), "01", new BigDecimal("1000.00000")),
                        tuple(notaCredito.getId(), "03", new BigDecimal("300.00000")));
    }
}
