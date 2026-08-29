package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

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
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.RegistrarCobroRequest;
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
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) de D6 (Release 3 /
 * Fase C, ver diseño de {@code cobro_factura}): el bloqueo pesimista de la factura padre en
 * {@code CobroFacturaService#registrar} solo puede observarse contra un motor real -- Mockito
 * (ya probado en {@code CobroFacturaServiceTest}) no reproduce una carrera de verdad.
 *
 * <p>Modelada literalmente sobre {@code ConsecutivoServiceTest}: {@code @Testcontainers
 * @SpringBootTest} con Postgres real y SIN contenedor de Vault (a diferencia de
 * {@code AislamientoMultiTenantTest}) -- {@code src/test/resources/application.properties} ya
 * stubea {@code VAULT_ROLE_ID}/{@code VAULT_SECRET_ID}, así que un contexto plano arranca sin
 * problema.
 */
@Testcontainers
@SpringBootTest
class CobroFacturaConcurrenciaTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // OBLIGATORIO, misma razón literal que ConsecutivoServiceTest: con una sola conexión en
        // el pool, el segundo hilo esperaría por la CONEXIÓN, no por la FILA, y la prueba de
        // concurrencia no probaría lo que dice probar.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
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
    private CobroFacturaService cobroFacturaService;

    private UUID empresaId;
    private UUID usuarioId;
    private UUID facturaId;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba");
        usuario.setEmail("usuario-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Concurrencia Cobro S.A.");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        Empresa empresa = empresaRepository.save(nueva);
        empresaId = empresa.getId();

        TenantContext.set(empresaId);

        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente Concurrencia Cobro");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("999999999");
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        cliente = clienteRepository.save(cliente);

        // '02' a plazo, total 1130.00000 (subtotal 1000.00000 + 13% IVA) -- mismo fixture
        // reutilizado sin cambios desde PR2/PR3/PR4 para esta magnitud exacta.
        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta("02");
        factura.setPlazoCredito(30);
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(new BigDecimal("1000.00000"));
        factura.setTotalImpuesto(new BigDecimal("130.00000"));
        factura.setTotal(new BigDecimal("1130.00000"));
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        factura = facturaRepository.save(factura);
        facturaId = factura.getId();

        // ACEPTADO forzado directamente por repositorio -- mismo patrón que
        // FacturaControllerTest#crearFacturaAPlazoAceptada: el flujo HTTP real de aceptación no
        // es el objeto de esta prueba.
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(facturaId);
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante("01");
        comprobante.setConsecutivo("cobro-conc-01");
        comprobante.setClaveNumerica("clave-cobro-concurrencia-0001");
        comprobante.setEstado("ACEPTADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        comprobante.setIntentosConsulta(0);
        comprobanteElectronicoRepository.save(comprobante);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void dosCobrosConcurrentesQueJuntosExcedenElSaldoDejanExactamenteUnoAceptado() throws Exception {
        UUID facturaIdFinal = facturaId;
        UUID empresaIdFinal = empresaId;
        UUID usuarioIdFinal = usuarioId;

        // Individualmente caben bajo el tope (700.00000 < 1130.00000), pero juntos (1400.00000)
        // lo exceden -- exactamente el escenario de D6.
        Callable<Boolean> intentoDeCobro = () -> {
            // Cada hilo fija su PROPIO TenantContext Y su PROPIO SecurityContextHolder: ambos son
            // ThreadLocal, así que ninguno se hereda del hilo principal de la prueba.
            TenantContext.set(empresaIdFinal);
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(usuarioIdFinal, null, List.of()));
            try {
                cobroFacturaService.registrar(facturaIdFinal,
                        new RegistrarCobroRequest(new BigDecimal("700.00000"), "04", null, null));
                return Boolean.TRUE;
            } catch (RuntimeException excepcionDeNegocioODeBaseDeDatos) {
                // Aserción sobre el INVARIANTE, no sobre la ruta de rechazo: exigir un tipo de
                // excepción concreto sobre-especifica la prueba -- con el lock tomado, el
                // pre-chequeo Java gana la carrera (MontoCobroExcedeSaldoException); sin el lock
                // ganaría el trigger de Postgres (SQLSTATE P0001). La prueba debe fallar en RED
                // por DOS éxitos, nunca por el nombre de la excepción del lado que pierde.
                return Boolean.FALSE;
            } finally {
                TenantContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Boolean> resultados;
        try {
            List<Callable<Boolean>> tareas = Stream.generate(() -> intentoDeCobro).limit(2).toList();
            List<Future<Boolean>> futuros = executor.invokeAll(tareas);
            resultados = futuros.stream()
                    .map(futuro -> {
                        try {
                            return futuro.get();
                        } catch (Exception excepcion) {
                            throw new RuntimeException(excepcion);
                        }
                    })
                    .toList();
        } finally {
            executor.shutdown();
        }

        long exitosos = resultados.stream().filter(Boolean::booleanValue).count();
        long fallidos = resultados.size() - exitosos;
        assertThat(exitosos).isEqualTo(1);
        assertThat(fallidos).isEqualTo(1);

        TenantContext.set(empresaId);
        BigDecimal totalCobrado = cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId);
        // compareTo, nunca equals: 1130.00000 y un total con escala distinta serían "distintos"
        // por equals aunque representen el mismo valor.
        assertThat(totalCobrado.compareTo(new BigDecimal("1130.00000"))).isLessThanOrEqualTo(0);

        List<CobroFactura> historial = cobroFacturaRepository.findByFacturaIdOrderByFechaCobroAscIdAsc(facturaId);
        assertThat(historial).hasSize(1);
    }
}
