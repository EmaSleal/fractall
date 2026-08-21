package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de {@link ComprobanteEmisionService} -- extracción de {@code FacturaService.java:469-487}
 * (Fase B, PR2, ver diseño D-B). {@code ComprobanteXmlCifradoDescargador},
 * {@code ComprobanteHaciendaEnvioService} y {@code ComprobanteXmlPersistenceService} se mockean
 * directamente (son dependencias del constructor de este servicio) -- ninguna ruta ejercitada aquí
 * necesita Vault ni una llamada de red real a Hacienda.
 */
@Testcontainers
@SpringBootTest
class ComprobanteEmisionServiceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

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
    private FacturaRepository facturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

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
        usuario.setNombre("Usuario de prueba ComprobanteEmision");
        usuario.setEmail("usuario-comprobante-emision-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa ComprobanteEmision S.A.");
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

    private Factura crearFacturaMinima(UUID clienteId) {
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
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        return facturaRepository.saveAndFlush(factura);
    }

    /**
     * D-B: {@code registrarComprobante} lleva {@code @Transactional(propagation = MANDATORY)} --
     * el invariante de la Fase 7 exige que el reclamo del consecutivo ruede junto con el llamador;
     * llamarlo SIN una transacción ya abierta debe fallar de forma ruidosa, no abrir una propia.
     */
    @Test
    void registrarComprobanteFueraDeUnaTransaccionExistenteLanzaIllegalTransactionState() {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> comprobanteEmisionService.registrarComprobante(
                factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa, ahoraUtc))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void registrarComprobanteDentroDeUnaTransaccionAsignaConsecutivoYClaveSegunElCodigoDelPerfil() {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ComprobanteElectronico comprobante = transactionTemplate.execute(status ->
                comprobanteEmisionService.registrarComprobante(
                        factura, TipoComprobantePerfil.NOTA_CREDITO, empresa, ahoraUtc));

        assertThat(comprobante).isNotNull();
        assertThat(comprobante.getTipoComprobante()).isEqualTo("03");
        assertThat(comprobante.getFacturaId()).isEqualTo(factura.getId());
        assertThat(comprobante.getEstado()).isEqualTo("GENERADO");
        assertThat(comprobante.getConsecutivo()).hasSize(20);
        // Segmento tipoComprobante embebido en el consecutivo formateado (posiciones 8-9, ver
        // ClaveNumericaGenerator#formatearConsecutivo: sucursal(3)+terminal(5)+tipo(2)+numero(10)).
        assertThat(comprobante.getConsecutivo().substring(8, 10)).isEqualTo("03");
        assertThat(comprobante.getClaveNumerica()).hasSize(50);
        // El mismo segmento de consecutivo debe estar embebido byte-idéntico en la clave numérica.
        String segmentoConsecutivoEnClave = comprobante.getClaveNumerica().substring(21, 41);
        assertThat(segmentoConsecutivoEnClave).isEqualTo(comprobante.getConsecutivo());

        ComprobanteElectronico persistido =
                comprobanteElectronicoRepository.findById(comprobante.getId()).orElseThrow();
        assertThat(persistido.getTipoComprobante()).isEqualTo("03");
    }

    @Test
    void reenviarConEstadoReenviableDescargaXmlReiniciaIntentosYReenviaAHacienda() {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ComprobanteElectronico comprobante = transactionTemplate.execute(status ->
                comprobanteEmisionService.registrarComprobante(
                        factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa, ahoraUtc));

        comprobante.setEstado("RECHAZADO");
        comprobante.setXmlComprobanteReferencia("empresas/ref/comprobante.xml.enc");
        comprobante.setIntentosEnvio(7);
        comprobanteElectronicoRepository.save(comprobante);

        String xmlFirmadoOriginal = "<FacturaElectronica>contenido</FacturaElectronica>";
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(anyString()))
                .thenReturn(xmlFirmadoOriginal.getBytes(StandardCharsets.UTF_8));

        ComprobanteElectronico resultado = comprobanteEmisionService.reenviar(factura.getId());

        assertThat(resultado.getIntentosEnvio()).isZero();
        verify(comprobanteXmlCifradoDescargador).descargarYDescifrar("empresas/ref/comprobante.xml.enc");
        verify(comprobanteHaciendaEnvioService, times(1))
                .enviarComprobante(org.mockito.ArgumentMatchers.eq(xmlFirmadoOriginal), any(ComprobanteElectronico.class));
    }

    @Test
    void reenviarConEstadoNoReenviableLanzaComprobanteNoReenviable() {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.execute(status -> comprobanteEmisionService.registrarComprobante(
                factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa, ahoraUtc));
        // Estado por defecto tras registrarComprobante es GENERADO -- no reenviable.

        assertThatThrownBy(() -> comprobanteEmisionService.reenviar(factura.getId()))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(comprobanteXmlCifradoDescargador, never()).descargarYDescifrar(anyString());
    }

    @Test
    void procesarXmlYEnvioDelegaEnComprobanteXmlPersistenceService() {
        UUID comprobanteId = UUID.randomUUID();

        comprobanteEmisionService.procesarXmlYEnvio(comprobanteId);

        verify(comprobanteXmlPersistenceService).generarYPersistirXml(comprobanteId);
    }
}
