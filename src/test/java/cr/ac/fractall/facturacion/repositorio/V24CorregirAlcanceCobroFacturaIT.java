package cr.ac.fractall.facturacion.repositorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.CobroFactura;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de la migración {@code V24__corregir_alcance_cobro_factura.sql} (Release 3 / Fase D,
 * PR1 de {@code reporte-flujo-caja} -- ver diseño obs #918, Decisión B11).
 *
 * <p>Root cause compartida por el trigger de escritura ({@code fn_validar_alcance_cobro_factura})
 * y la vista de lectura ({@code factura_estado_cobro}): una Nota de Crédito/Débito hereda
 * {@code condicion_venta} de su factura origen (ver {@code NotaCreditoDebitoService:270,338}), así
 * que pasa cualquier chequeo que mire solo {@code condicion_venta} sin confirmar además que la fila
 * es realmente un documento de venta cobrable ({@code tipo_comprobante IN ('01','04')}).
 *
 * <p>Los métodos {@link #postCobrosContraFacturaDeNotaCreditoEsRechazadoPorElTrigger} y
 * {@link #notaCreditoAceptadaYaNoApareceComoFilaPendienteEnLaVista} documentaron el defecto ANTES
 * de que existiera V24 (RED: ambos pasaban afirmando el comportamiento defectuoso -- inserción
 * exitosa contra la NC propia, fila espuria en la vista). Tras crear V24, se reescribieron para
 * afirmar el comportamiento corregido (GREEN) -- ver tasks 1.1/1.2/1.4 de
 * {@code sdd/reporte-flujo-caja/tasks}. El historial de ese RED queda documentado aquí, en el
 * Javadoc, en vez de mantenerse como una prueba permanente que afirma un bug ya corregido.
 */
@Testcontainers
@SpringBootTest
class V24CorregirAlcanceCobroFacturaIT {

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
    private FacturaEstadoCobroRepository facturaEstadoCobroRepository;

    private Empresa empresa;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        // Ver AislamientoMultiTenantTest: hace falta un empresa_id resuelto en contexto para abrir
        // cualquier EntityManager de este SessionFactory, aunque Usuario/Empresa no sean tenant-aware.
        TenantContext.set(UUID.randomUUID());

        usuario = new Usuario();
        usuario.setNombre("Usuario de prueba V24");
        usuario.setEmail("usuario-v24-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        empresa = nuevaEmpresa("Empresa V24 S.A.", usuario.getId());
        empresa = empresaRepository.save(empresa);

        TenantContext.set(empresa.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Empresa nuevaEmpresa(String razonSocial, UUID creadoPor) {
        Empresa empresa = new Empresa();
        empresa.setRazonSocial(razonSocial);
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(creadoPor);
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        return empresa;
    }

    private static Cliente nuevoCliente(String nombre, String numeroIdentificacion) {
        Cliente cliente = new Cliente();
        cliente.setNombre(nombre);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion(numeroIdentificacion);
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return cliente;
    }

    private static Factura nuevaFactura(UUID clienteId, UUID creadoPor) {
        Factura factura = new Factura();
        factura.setClienteId(clienteId);
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(new BigDecimal("1000.00000"));
        factura.setTotalImpuesto(new BigDecimal("130.00000"));
        factura.setTotal(new BigDecimal("1130.00000"));
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(creadoPor);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        return factura;
    }

    /** Ver AislamientoMultiTenantTest#nuevaFacturaAPlazo: el CHECK de V4 exige plazo_credito para '02'. */
    private static Factura nuevaFacturaAPlazo(UUID clienteId, UUID creadoPor, String condicionVenta) {
        Factura factura = nuevaFactura(clienteId, creadoPor);
        factura.setCondicionVenta(condicionVenta);
        if ("02".equals(condicionVenta)) {
            factura.setPlazoCredito(30);
        }
        return factura;
    }

    private static ComprobanteElectronico nuevoComprobante(
            UUID facturaId, String tipoComprobante, String consecutivo, String claveNumerica) {
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(facturaId);
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante(tipoComprobante);
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("GENERADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        comprobante.setIntentosConsulta(0);
        return comprobante;
    }

    private static CobroFactura nuevoCobro(UUID facturaId, String monto, UUID registradoPor) {
        CobroFactura cobro = new CobroFactura();
        cobro.setFacturaId(facturaId);
        cobro.setMontoCobrado(new BigDecimal(monto));
        cobro.setFechaCobro(LocalDateTime.now());
        cobro.setMedioPago("04");
        cobro.setReferencia(null);
        cobro.setRegistradoPor(registradoPor);
        cobro.setCreateDate(LocalDateTime.now());
        return cobro;
    }

    private static Throwable raizDe(Throwable error) {
        Throwable causa = error;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        return causa;
    }

    /**
     * Arma una factura origen a plazo (condicion_venta = '02') con comprobante '01' ACEPTADO, y una
     * Nota de Crédito ACEPTADA que la referencia, heredando (a mano, como haría
     * {@code NotaCreditoDebitoService}) {@code condicionVenta}/{@code plazoCredito} de la factura
     * origen -- esta herencia es la raíz del defecto que V24 corrige.
     */
    private Factura[] crearOrigenYNotaCreditoAceptada(String numeroIdentificacionBase) {
        Cliente cliente = clienteRepository.save(
                nuevoCliente("Cliente V24 " + numeroIdentificacionBase, numeroIdentificacionBase));
        Factura origen = facturaRepository.save(
                nuevaFacturaAPlazo(cliente.getId(), empresa.getCreadoPor(), "02"));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(origen.getId(), "01", "v24-origen-" + numeroIdentificacionBase,
                        "clave-v24-origen-" + numeroIdentificacionBase));

        Factura notaCredito = nuevaFacturaAPlazo(cliente.getId(), empresa.getCreadoPor(), "02");
        notaCredito.setFacturaReferenciaId(origen.getId());
        notaCredito.setTotal(new BigDecimal("130.00000"));
        notaCredito = facturaRepository.saveAndFlush(notaCredito);

        ComprobanteElectronico comprobanteNc = nuevoComprobante(
                notaCredito.getId(), "03", "v24-nc-" + numeroIdentificacionBase,
                "clave-v24-nc-" + numeroIdentificacionBase);
        comprobanteNc.setEstado("ACEPTADO");
        comprobanteElectronicoRepository.saveAndFlush(comprobanteNc);

        return new Factura[] {origen, notaCredito};
    }

    /**
     * Tarea 1.1/1.4: pre-V24, un cobro contra el {@code factura_id} de una Nota de Crédito ACEPTADA
     * (cuyo {@code condicion_venta} heredado es '02', dentro del rango permitido) se insertaba SIN
     * error, porque {@code fn_validar_alcance_cobro_factura} solo miraba {@code condicion_venta}.
     * Post-V24, el trigger también exige {@code tipo_comprobante IN ('01','04')} y rechaza el
     * intento.
     */
    @Test
    void postCobrosContraFacturaDeNotaCreditoEsRechazadoPorElTrigger() {
        Factura[] fixture = crearOrigenYNotaCreditoAceptada("700000001");
        Factura notaCredito = fixture[1];

        CobroFactura cobroContraNc = nuevoCobro(notaCredito.getId(), "50.00000", usuario.getId());

        // GREEN (post-V24, tarea 1.4): fn_validar_alcance_cobro_factura ahora tambien exige
        // tipo_comprobante IN ('01','04') -- la NC (tipo_comprobante '03') es rechazada aunque su
        // condicion_venta heredada ('02') este dentro del rango permitido. Pre-V24 este mismo test
        // documentaba lo contrario: el insert SUCEDIA (ver Javadoc de la clase).
        Exception excepcion = assertThrows(Exception.class,
                () -> cobroFacturaRepository.saveAndFlush(cobroContraNc));
        assertThat(raizDe(excepcion).getMessage())
                .as("V24 debe rechazar el cobro contra la NC por tipo_comprobante, no solo por condicion_venta")
                .contains("tipo_comprobante")
                .contains("03");
    }

    /**
     * Tarea 1.2/1.4: pre-V24, {@code factura_estado_cobro} reportaba una fila PENDIENTE para el
     * {@code factura_id} de la propia Nota de Crédito (misma causa raíz: sin filtro de
     * {@code tipo_comprobante} en el base set de la vista). Post-V24, esa fila desaparece por
     * completo.
     */
    @Test
    void notaCreditoAceptadaYaNoApareceComoFilaPendienteEnLaVista() {
        Factura[] fixture = crearOrigenYNotaCreditoAceptada("700000002");
        Factura notaCredito = fixture[1];

        // GREEN (post-V24, tarea 1.4): factura_estado_cobro ahora exige tipo_comprobante IN
        // ('01','04') en su base set -- la fila espuria de la NC desaparece por completo. Pre-V24
        // este mismo test documentaba lo contrario: la fila existia con estado PENDIENTE (ver
        // Javadoc de la clase).
        assertThat(facturaEstadoCobroRepository.findByFacturaId(notaCredito.getId()))
                .as("V24 debe eliminar la fila espuria de la NC en factura_estado_cobro")
                .isEmpty();
    }

    /**
     * Tarea 1.5 -- no-regresión: una Factura Electrónica ('01') real, ACEPTADA, con
     * condicion_venta '02' (crédito), debe seguir apareciendo en la vista con su saldo correcto
     * tras V24 -- el fix añade un filtro, no debe excluir facturas legítimamente cobrables.
     */
    @Test
    void facturaCobrableRealSigueApareciendoEnLaVistaSinCambios() {
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente V24 No Regresion", "700000003"));
        Factura facturaCredito = facturaRepository.save(
                nuevaFacturaAPlazo(cliente.getId(), empresa.getCreadoPor(), "02"));
        ComprobanteElectronico comprobante = nuevoComprobante(
                facturaCredito.getId(), "01", "v24-noregresion-01", "clave-v24-noregresion-0001");
        comprobante.setEstado("ACEPTADO");
        comprobanteElectronicoRepository.saveAndFlush(comprobante);
        // facturaCredito.total = 1130.00000 (default de nuevaFactura)

        FacturaEstadoCobro estado = facturaEstadoCobroRepository.findByFacturaId(facturaCredito.getId())
                .orElseThrow();
        assertThat(estado.getEstadoCobro()).isEqualTo("PENDIENTE");
        assertThat(estado.getTotal()).isEqualByComparingTo("1130.00000");
        assertThat(estado.getSaldoPendiente()).isEqualByComparingTo("1130.00000");

        CobroFactura cobroReal = cobroFacturaRepository.saveAndFlush(
                nuevoCobro(facturaCredito.getId(), "500.00000", usuario.getId()));
        assertThat(cobroReal.getId()).isNotNull();

        FacturaEstadoCobro estadoParcial = facturaEstadoCobroRepository.findByFacturaId(facturaCredito.getId())
                .orElseThrow();
        assertThat(estadoParcial.getEstadoCobro()).isEqualTo("PARCIAL");
        assertThat(estadoParcial.getSaldoPendiente()).isEqualByComparingTo("630.00000");
    }
}
