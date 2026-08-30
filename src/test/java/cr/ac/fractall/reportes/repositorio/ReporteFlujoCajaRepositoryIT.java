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
import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;
import cr.ac.fractall.facturacion.repositorio.CobroFacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaEstadoCobroRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración de {@link ReporteFlujoCajaRepository} -- Q1 ({@code buscarVentasEnPeriodo})
 * y Q2 ({@code buscarCobrosEnPeriodo}) llegaron en la PR2 de {@code reporte-flujo-caja} (Release 3
 * / Fase D, ver el diseño obs #918). Esta PR (3 de 7) agrega Q3 ({@code
 * buscarCarteraPendienteAlCorte}) -- ver {@code sdd/reporte-flujo-caja/tasks}, Fase 3.
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

    @Autowired
    private FacturaEstadoCobroRepository facturaEstadoCobroRepository;

    /** Período fijo de agosto 2026 (media-noche inclusiva / media-noche exclusiva), usado por todas las pruebas. */
    private static final LocalDateTime DESDE_AGOSTO = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime HASTA_AGOSTO_EXCLUSIVO = LocalDateTime.of(2026, 9, 1, 0, 0);

    /**
     * Corte fijo para las pruebas de Q3 (cartera): {@code fechaCorte = 2026-08-15},
     * {@code corteExclusivo = 2026-08-16T00:00} -- la forma medio-abierta que el servicio (Fase 4)
     * resuelve como {@code fechaCorte.plusDays(1).atStartOfDay()} (Decisión B9, finding 5).
     */
    private static final LocalDateTime CORTE_EXCLUSIVO = LocalDateTime.of(2026, 8, 16, 0, 0);

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

    /**
     * Arma una Nota de Crédito que referencia {@code origenId}, heredando {@code condicionVenta}
     * (a mano, como haría {@code NotaCreditoDebitoService}), con su propio comprobante
     * {@code tipo_comprobante='03'} en el {@code estado}/{@code fechaEmision} indicados -- usado por
     * las pruebas de cota (2) y de exclusión de NC propia (Q3).
     */
    private Factura crearNotaCredito(
            UUID clienteId, UUID creadoPor, String condicionVenta, UUID origenId, String total,
            String estado, LocalDateTime fechaEmision, String consecutivo) {
        Factura notaCredito = nuevaFactura(clienteId, creadoPor, condicionVenta, total);
        notaCredito.setFacturaReferenciaId(origenId);
        notaCredito = facturaRepository.saveAndFlush(notaCredito);
        ComprobanteElectronico comprobanteNc = nuevoComprobante(
                notaCredito.getId(), "03", consecutivo, "clave-" + consecutivo, fechaEmision);
        comprobanteNc.setEstado(estado);
        comprobanteElectronicoRepository.saveAndFlush(comprobanteNc);
        return notaCredito;
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

    // ─────────────────────────────────────────────────────────────────────────
    // Q3 -- cartera pendiente punto-en-el-tiempo (PR3)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aislamiento por tenant para Q3 -- una factura pendiente de OTRO tenant, dentro del mismo
     * corte, nunca debe aparecer en la cartera del tenant actual.
     */
    @Test
    void carteraDeOtroTenantNoApareceEnElCorte() {
        Cliente clienteA = clienteRepository.save(nuevoCliente("Cliente Cartera A", "800000010"));
        Factura facturaA = facturaRepository.save(
                nuevaFactura(clienteA.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaA.getId(), "01", "cartera-a-001", "clave-cartera-a-001",
                LocalDateTime.of(2026, 8, 1, 9, 0)));

        Usuario usuarioB = usuarioRepository.save(nuevoUsuario("D"));
        Empresa empresaB = empresaRepository.save(nuevaEmpresa("Empresa Flujo Caja D S.A.", usuarioB.getId()));
        TenantContext.set(empresaB.getId());
        Cliente clienteB = clienteRepository.save(nuevoCliente("Cliente Cartera B", "800000011"));
        Factura facturaB = facturaRepository.save(
                nuevaFactura(clienteB.getId(), usuarioB.getId(), "02", "2000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaB.getId(), "01", "cartera-b-001", "clave-cartera-b-001",
                LocalDateTime.of(2026, 8, 1, 9, 0)));

        TenantContext.set(empresaA.getId());
        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        assertThat((UUID) filas.get(0)[0]).isEqualTo(facturaA.getId());
    }

    /**
     * Cota (1) -- un cobro registrado DESPUÉS del corte no se netea: la factura debe seguir
     * mostrando el saldo previo al cobro.
     */
    @Test
    void carteraExcluyeCobrosPosterioresAlCorte() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Cota1", "800000012"));
        Factura factura = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                factura.getId(), "01", "cota1-001", "clave-cota1-001",
                LocalDateTime.of(2026, 8, 1, 9, 0)));
        // Cobro DESPUES del corte (2026-08-15) -- no debe netearse.
        cobroFacturaRepository.saveAndFlush(nuevoCobro(
                factura.getId(), "400.00000", "04", LocalDateTime.of(2026, 8, 20, 10, 0), usuarioA.getId()));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        Object[] fila = filas.get(0);
        assertThat((UUID) fila[0]).isEqualTo(factura.getId());
        assertThat((BigDecimal) fila[6]).as("saldo_pendiente").isEqualByComparingTo("1000.00000");
    }

    /**
     * Cota (2) -- una NC aceptada emitida DESPUÉS del corte no se netea: el {@code total_neto} de
     * la factura origen debe conservar el total bruto, sin descontar esa NC.
     */
    @Test
    void carteraExcluyeNotasCreditoEmitidasDespuesDelCorte() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Cota2", "800000013"));
        Factura origen = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                origen.getId(), "01", "cota2-origen", "clave-cota2-origen",
                LocalDateTime.of(2026, 8, 1, 9, 0)));
        // NC aceptada emitida DESPUES del corte -- no debe netearse.
        crearNotaCredito(cliente.getId(), usuarioA.getId(), "02", origen.getId(), "300.00000",
                "ACEPTADO", LocalDateTime.of(2026, 8, 20, 11, 0), "cota2-nc");

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        Object[] fila = filas.get(0);
        assertThat((UUID) fila[0]).isEqualTo(origen.getId());
        assertThat((BigDecimal) fila[4]).as("total_neto").isEqualByComparingTo("1000.00000");
        assertThat((BigDecimal) fila[6]).as("saldo_pendiente").isEqualByComparingTo("1000.00000");
    }

    /**
     * Cota (3) -- una factura cuya PROPIA {@code fecha_emision} es posterior al corte se excluye
     * POR COMPLETO del resultado, sin importar su saldo.
     */
    @Test
    void carteraExcluyeFacturasEmitidasDespuesDelCorte() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Cota3", "800000014"));
        Factura factura = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        // Emitida DESPUES del corte -- debe desaparecer por completo del resultado.
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                factura.getId(), "01", "cota3-001", "clave-cota3-001",
                LocalDateTime.of(2026, 8, 20, 9, 0)));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).isEmpty();
    }

    /**
     * Forma medio-abierta (finding 5, Decisión B9) -- un cobro registrado el MISMO día del corte
     * (dentro de las 24h de {@code fechaCorte}) SÍ debe netearse: {@code corteExclusivo} es
     * media-noche del día SIGUIENTE, comparado con {@code <} estricto.
     */
    @Test
    void carteraIncluyeCobroRegistradoElMismoDiaDelCorte() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente MedioAbierto", "800000015"));
        Factura factura = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                factura.getId(), "01", "medioabierto-001", "clave-medioabierto-001",
                LocalDateTime.of(2026, 8, 1, 9, 0)));
        // Cobro el MISMO dia del corte (2026-08-15), avanzada la tarde -- debe netearse.
        cobroFacturaRepository.saveAndFlush(nuevoCobro(
                factura.getId(), "400.00000", "04", LocalDateTime.of(2026, 8, 15, 23, 59), usuarioA.getId()));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        assertThat((BigDecimal) filas.get(0)[6]).as("saldo_pendiente").isEqualByComparingTo("600.00000");
    }

    /**
     * Requisito "Cartera Pendiente Equivalence to factura_estado_cobro at Today" -- con
     * {@code fechaCorte = hoy}, el saldo de Q3 para una factura con cobro parcial Y una NC
     * aceptada, ambos antes de hoy, debe coincidir EXACTAMENTE con el {@code saldo_pendiente} de la
     * vista {@code factura_estado_cobro} para esa misma factura ({@code isEqualByComparingTo}, no
     * {@code isEqualTo}) -- post-V24 (Fase 1, Decisión B11), la vista ya no reporta la NC como su
     * propia fila espuria, así que la equivalencia estricta es válida (design revision 2, superando
     * el plan original de divergencia documentada).
     */
    @Test
    void carteraAlDiaDeHoyCoincideConLaVistaFacturaEstadoCobroInclusoConUnaNotaCreditoAceptada() {
        LocalDateTime ayer = LocalDateTime.now().minusDays(1);
        LocalDateTime corteExclusivoHoy = LocalDateTime.now().plusDays(1).toLocalDate().atStartOfDay();

        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Equivalencia", "800000016"));
        Factura origen = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                origen.getId(), "01", "equiv-origen", "clave-equiv-origen", ayer));
        cobroFacturaRepository.saveAndFlush(nuevoCobro(
                origen.getId(), "200.00000", "04", ayer, usuarioA.getId()));
        crearNotaCredito(cliente.getId(), usuarioA.getId(), "02", origen.getId(), "300.00000",
                "ACEPTADO", ayer, "equiv-nc");

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), corteExclusivoHoy);

        assertThat(filas).hasSize(1);
        BigDecimal saldoQ3 = (BigDecimal) filas.get(0)[6];

        FacturaEstadoCobro estadoVista = facturaEstadoCobroRepository.findByFacturaId(origen.getId())
                .orElseThrow();

        assertThat(saldoQ3)
                .as("Q3 debe coincidir exactamente con factura_estado_cobro post-V24")
                .isEqualByComparingTo(estadoVista.getSaldoPendiente());
        assertThat(saldoQ3).isEqualByComparingTo("500.00000");
    }

    /**
     * Requisito "Ventas Series Includes All condicion_venta Values", asimetría intencional con
     * ventas -- una factura {@code condicion_venta='01'} (contado) NUNCA debe aparecer en la
     * cartera, aunque esté ACEPTADA antes del corte.
     */
    @Test
    void carteraExcluyeFacturaContado() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Contado Cartera", "800000017"));
        Factura facturaContado = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "01", "1130.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                facturaContado.getId(), "01", "contado-cartera-001", "clave-contado-cartera-001",
                LocalDateTime.of(2026, 8, 1, 8, 0)));

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).isEmpty();
    }

    /**
     * Divergencia B (finding 3 del diseño) -- una factura cuyo comprobante fue RECHAZADO nunca debe
     * aparecer en cartera: Q3 exige {@code ce.estado = 'ACEPTADO'} explícitamente, a diferencia de
     * la vista, que no filtra {@code estado} en absoluto.
     */
    @Test
    void carteraExcluyeComprobanteRechazado() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Rechazado", "800000018"));
        Factura factura = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        ComprobanteElectronico comprobanteRechazado = nuevoComprobante(
                factura.getId(), "01", "rechazado-001", "clave-rechazado-001",
                LocalDateTime.of(2026, 8, 1, 9, 0));
        comprobanteRechazado.setEstado("RECHAZADO");
        comprobanteElectronicoRepository.saveAndFlush(comprobanteRechazado);

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).isEmpty();
    }

    /**
     * Divergencia A (finding 1 del diseño) -- una Nota de Crédito ACEPTADA, con
     * {@code condicion_venta} heredada dentro del rango permitido, NUNCA debe aparecer en cartera
     * como su PROPIA fila: {@code ce.tipo_comprobante} de la NC es {@code '03'}, fuera del filtro
     * {@code IN ('01','04')} del base set -- esta consulta mantiene su propio filtro explícito
     * independientemente del estado de la vista (Decisión B1).
     */
    @Test
    void carteraExcluyeNotaCreditoComoFacturaPropia() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente NC Propia Cartera", "800000019"));
        Factura origen = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                origen.getId(), "01", "ncpropia-origen", "clave-ncpropia-origen",
                LocalDateTime.of(2026, 8, 1, 9, 0)));
        Factura notaCredito = crearNotaCredito(cliente.getId(), usuarioA.getId(), "02", origen.getId(),
                "300.00000", "ACEPTADO", LocalDateTime.of(2026, 8, 2, 9, 0), "ncpropia-nc");

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas)
                .extracting(fila -> (UUID) fila[0])
                .as("la NC no debe aparecer como su propia fila de cartera")
                .doesNotContain(notaCredito.getId());
        assertThat(filas).hasSize(1);
        assertThat((UUID) filas.get(0)[0]).isEqualTo(origen.getId());
    }

    /**
     * Requisito "Fully-Credited Invoice Reports as Settled" -- una factura totalmente acreditada
     * por una NC aceptada (antes del corte) por el mismo monto debe traer {@code total_neto <= 0} y
     * {@code saldo_pendiente <= 0}, nunca positivo: el conteo de facturas pendientes (Fase 4,
     * servicio) filtra {@code saldoPendiente > 0}, así que esta fila no debe contarse como
     * pendiente. Esta prueba pin-ea el valor crudo que la fila trae (la exclusión del conteo es
     * responsabilidad del servicio, no de esta consulta -- ver javadoc de
     * {@link FilaCarteraFactura}).
     */
    @Test
    void carteraConFacturaTotalmenteAcreditadaNoCuentaComoPendiente() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Totalmente Acreditada", "800000020"));
        Factura origen = facturaRepository.save(
                nuevaFactura(cliente.getId(), usuarioA.getId(), "02", "1000.00000"));
        comprobanteElectronicoRepository.saveAndFlush(nuevoComprobante(
                origen.getId(), "01", "totalcredito-origen", "clave-totalcredito-origen",
                LocalDateTime.of(2026, 8, 1, 9, 0)));
        // NC aceptada por el TOTAL de la factura, antes del corte -- total_neto debe caer a 0.
        crearNotaCredito(cliente.getId(), usuarioA.getId(), "02", origen.getId(), "1000.00000",
                "ACEPTADO", LocalDateTime.of(2026, 8, 2, 9, 0), "totalcredito-nc");

        List<Object[]> filas = reporteFlujoCajaRepository.buscarCarteraPendienteAlCorte(
                empresaA.getId(), CORTE_EXCLUSIVO);

        assertThat(filas).hasSize(1);
        Object[] fila = filas.get(0);
        assertThat((UUID) fila[0]).isEqualTo(origen.getId());
        assertThat((BigDecimal) fila[4]).as("total_neto").isEqualByComparingTo("0.00000");
        assertThat((BigDecimal) fila[6]).as("saldo_pendiente").isEqualByComparingTo("0.00000");
    }
}
