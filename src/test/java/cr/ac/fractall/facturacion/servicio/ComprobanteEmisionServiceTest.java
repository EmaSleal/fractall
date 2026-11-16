package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    // ========== reenvío manual (T-004, ADR-055 y ADR-056) ==========

    private ComprobanteElectronico comprobanteEnEstado(String estado, String xmlReferencia) {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ComprobanteElectronico comprobante = transactionTemplate.execute(status ->
                comprobanteEmisionService.registrarComprobante(
                        factura, TipoComprobantePerfil.FACTURA_ELECTRONICA, empresa, ahoraUtc));
        comprobante.setEstado(estado);
        comprobante.setXmlComprobanteReferencia(xmlReferencia);
        comprobante.setIntentosEnvio(7);
        return comprobanteElectronicoRepository.save(comprobante);
    }

    private ComprobanteElectronico recargar(ComprobanteElectronico comprobante) {
        return comprobanteElectronicoRepository.findById(comprobante.getId()).orElseThrow();
    }

    private void fijarArrendamiento(ComprobanteElectronico comprobante, LocalDateTime hasta) {
        ComprobanteElectronico actual = recargar(comprobante);
        actual.setReenvioReclamadoHasta(hasta);
        comprobanteElectronicoRepository.save(actual);
    }

    private void stubXmlFirmado(String xml) {
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(anyString()))
                .thenReturn(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void estadosReenviablesSonExactamenteFirmadoYError() {
        assertThat(ComprobanteEmisionService.ESTADOS_REENVIABLES).containsExactlyInAnyOrder("FIRMADO", "ERROR");
    }

    @Test
    void reenviarConEstadoNoReenviableLanzaExcepcionSinLlamarAHacienda() {
        for (String estado : new String[] {"RECHAZADO", "ENVIADO", "GENERADO", "ACEPTADO"}) {
            ComprobanteElectronico comprobante = comprobanteEnEstado(estado, "empresas/ref/" + estado + ".xml.enc");
            org.mockito.Mockito.reset(comprobanteXmlCifradoDescargador, comprobanteHaciendaEnvioService);

            assertThatThrownBy(() -> comprobanteEmisionService.reenviar(comprobante.getFacturaId()))
                    .as("estado %s no debe ser reenviable", estado)
                    .isInstanceOf(ComprobanteNoReenviableException.class);

            verifyNoInteractions(comprobanteXmlCifradoDescargador, comprobanteHaciendaEnvioService);
            ComprobanteElectronico persistido = recargar(comprobante);
            assertThat(persistido.getReenviosManuales()).as("estado %s", estado).isZero();
            assertThat(persistido.getIntentosEnvio()).as("estado %s", estado).isEqualTo(7);
        }
    }

    @Test
    void reenviarConEstadoReenviablePeroSinXmlPersistidoLanzaComprobanteNoReenviable() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("FIRMADO", null);

        assertThatThrownBy(() -> comprobanteEmisionService.reenviar(comprobante.getFacturaId()))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verifyNoInteractions(comprobanteXmlCifradoDescargador, comprobanteHaciendaEnvioService);
    }

    @Test
    void reenviarFirmadoHaceElPostSinConsultarYReclamaElComprobante() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("FIRMADO", "empresas/ref/firmado.xml.enc");
        stubXmlFirmado("<FacturaElectronica>firmado</FacturaElectronica>");

        comprobanteEmisionService.reenviar(comprobante.getFacturaId());

        verify(comprobanteHaciendaEnvioService, times(1)).reenviarComprobante(
                eq("<FacturaElectronica>firmado</FacturaElectronica>"), any(ComprobanteElectronico.class));
        verify(comprobanteHaciendaEnvioService, never()).consultarParaReenvio(any());
        ComprobanteElectronico persistido = recargar(comprobante);
        assertThat(persistido.getEstado()).isEqualTo("FIRMADO");
        assertThat(persistido.getIntentosEnvio()).isZero();
        assertThat(persistido.getReenviosManuales()).isEqualTo(1);
    }

    @Test
    void reenviarErrorConClaveConocidaPorHaciendaNoHaceElPost() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("ERROR", "empresas/ref/error.xml.enc");
        when(comprobanteHaciendaEnvioService.consultarParaReenvio(any()))
                .thenReturn(ComprobanteHaciendaEnvioService.ResultadoConsultaReenvio.APLICADA);

        comprobanteEmisionService.reenviar(comprobante.getFacturaId());

        verify(comprobanteHaciendaEnvioService, times(1)).consultarParaReenvio(any(ComprobanteElectronico.class));
        verify(comprobanteHaciendaEnvioService, never()).reenviarComprobante(anyString(), any());
        verifyNoInteractions(comprobanteXmlCifradoDescargador);
    }

    @Test
    void reenviarErrorConClaveDesconocidaPorHaciendaHaceElPostUnaVez() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("ERROR", "empresas/ref/error.xml.enc");
        stubXmlFirmado("<FacturaElectronica>error</FacturaElectronica>");
        when(comprobanteHaciendaEnvioService.consultarParaReenvio(any()))
                .thenReturn(ComprobanteHaciendaEnvioService.ResultadoConsultaReenvio.CLAVE_DESCONOCIDA);

        comprobanteEmisionService.reenviar(comprobante.getFacturaId());

        verify(comprobanteHaciendaEnvioService, times(1)).reenviarComprobante(
                eq("<FacturaElectronica>error</FacturaElectronica>"), any(ComprobanteElectronico.class));
    }

    @Test
    void reenviarErrorConConsultaAmbiguaNoHaceElPostNiRechazaYLiberaElArrendamiento() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("ERROR", "empresas/ref/error.xml.enc");
        when(comprobanteHaciendaEnvioService.consultarParaReenvio(any()))
                .thenReturn(ComprobanteHaciendaEnvioService.ResultadoConsultaReenvio.AMBIGUA);

        comprobanteEmisionService.reenviar(comprobante.getFacturaId());

        verify(comprobanteHaciendaEnvioService, never()).reenviarComprobante(anyString(), any());
        ComprobanteElectronico persistido = recargar(comprobante);
        assertThat(persistido.getEstado()).isEqualTo("ERROR");
        assertThat(persistido.getReenvioReclamadoHasta()).isNull();
        assertThat(persistido.getMensajeRespuesta()).isEqualTo(ComprobanteEmisionService.MENSAJE_CONSULTA_AMBIGUA);
    }

    /**
     * Cubre también que la entidad se recarga tras el reclamo: la escritura final de la rama
     * ambigua guarda la entidad completa, y si fuera la copia previa al reclamo dejaría
     * {@code reenvios_manuales} en 0.
     */
    @Test
    void cadaReenvioManualIncrementaReenviosManualesYElArrendamientoSeLiberaParaElSiguiente() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("ERROR", "empresas/ref/error.xml.enc");
        when(comprobanteHaciendaEnvioService.consultarParaReenvio(any()))
                .thenReturn(ComprobanteHaciendaEnvioService.ResultadoConsultaReenvio.AMBIGUA);

        comprobanteEmisionService.reenviar(comprobante.getFacturaId());
        comprobanteEmisionService.reenviar(comprobante.getFacturaId());

        ComprobanteElectronico persistido = recargar(comprobante);
        assertThat(persistido.getReenviosManuales()).isEqualTo(2);
        assertThat(persistido.getIntentosEnvio()).isZero();
        assertThat(persistido.getReenvioReclamadoHasta()).isNull();
        verify(comprobanteHaciendaEnvioService, times(2)).consultarParaReenvio(any());
    }

    @Test
    void reenviarConArrendamientoVigenteResponde409SinLlamarAHacienda() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("FIRMADO", "empresas/ref/firmado.xml.enc");
        fijarArrendamiento(comprobante, LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> comprobanteEmisionService.reenviar(comprobante.getFacturaId()))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verifyNoInteractions(comprobanteXmlCifradoDescargador, comprobanteHaciendaEnvioService);
        assertThat(recargar(comprobante).getReenviosManuales()).isZero();
    }

    @Test
    void reenviarConArrendamientoVencidoVuelveAReclamar() {
        for (String estado : new String[] {"FIRMADO", "ERROR"}) {
            ComprobanteElectronico comprobante = comprobanteEnEstado(estado, "empresas/ref/" + estado + ".xml.enc");
            // Simula una caída a mitad de un reenvío anterior: el arrendamiento quedó escrito y venció.
            fijarArrendamiento(comprobante, LocalDateTime.now().minusMinutes(30));
            org.mockito.Mockito.reset(comprobanteXmlCifradoDescargador, comprobanteHaciendaEnvioService);
            stubXmlFirmado("<FacturaElectronica/>");
            when(comprobanteHaciendaEnvioService.consultarParaReenvio(any()))
                    .thenReturn(ComprobanteHaciendaEnvioService.ResultadoConsultaReenvio.CLAVE_DESCONOCIDA);

            comprobanteEmisionService.reenviar(comprobante.getFacturaId());

            verify(comprobanteHaciendaEnvioService, times(1)).reenviarComprobante(anyString(), any());
            assertThat(recargar(comprobante).getReenviosManuales()).as("estado %s", estado).isEqualTo(1);
        }
    }

    @Test
    void siElReenvioFallaAntesDeLaEscrituraFinalElArrendamientoSeLibera() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("FIRMADO", "empresas/ref/firmado.xml.enc");
        when(comprobanteXmlCifradoDescargador.descargarYDescifrar(anyString()))
                .thenThrow(new IllegalStateException("Object Storage no disponible"));

        assertThatThrownBy(() -> comprobanteEmisionService.reenviar(comprobante.getFacturaId()))
                .isInstanceOf(IllegalStateException.class);

        ComprobanteElectronico persistido = recargar(comprobante);
        assertThat(persistido.getReenvioReclamadoHasta()).isNull();
        assertThat(persistido.getEstado()).isEqualTo("FIRMADO");
    }

    @Test
    void elReclamoUsaElRelojDeLaBaseYRespetaElTenant() {
        ComprobanteElectronico comprobante = comprobanteEnEstado("ERROR", "empresas/ref/error.xml.enc");

        assertThat(comprobanteElectronicoRepository.reclamarReenvio(comprobante.getId(), UUID.randomUUID(), 600))
                .as("otra empresa no puede reclamar el comprobante").isZero();
        assertThat(comprobanteElectronicoRepository.reclamarReenvio(comprobante.getId(), empresa.getId(), 600))
                .isEqualTo(1);
        assertThat(comprobanteElectronicoRepository.reclamarReenvio(comprobante.getId(), empresa.getId(), 600))
                .as("arrendamiento vigente").isZero();

        LocalDateTime hasta = recargar(comprobante).getReenvioReclamadoHasta();
        LocalDateTime ahoraBase = jdbcTemplate.queryForObject("SELECT now()::timestamp", LocalDateTime.class);
        assertThat(hasta).isBetween(ahoraBase.plusSeconds(590), ahoraBase.plusSeconds(601));
    }

    @Test
    void dosReenviosSimultaneosProducenComoMaximoUnaLlamadaAHacienda() throws Exception {
        ComprobanteElectronico comprobante = comprobanteEnEstado("FIRMADO", "empresas/ref/firmado.xml.enc");
        stubXmlFirmado("<FacturaElectronica/>");
        java.util.concurrent.CountDownLatch dentroDeHacienda = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch liberarHacienda = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger llamadas = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocacion -> {
            llamadas.incrementAndGet();
            dentroDeHacienda.countDown();
            liberarHacienda.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(comprobanteHaciendaEnvioService).reenviarComprobante(anyString(), any());

        UUID empresaId = empresa.getId();
        UUID facturaId = comprobante.getFacturaId();
        java.util.concurrent.CyclicBarrier salida = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Object> tarea = () -> {
            TenantContext.set(empresaId);
            try {
                salida.await();
                comprobanteEmisionService.reenviar(facturaId);
                return "ok";
            } catch (ComprobanteNoReenviableException e) {
                // El perdedor debe recibir el 409 mientras el ganador sigue dentro de Hacienda.
                liberarHacienda.countDown();
                return e;
            } finally {
                TenantContext.clear();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Object> a = pool.submit(tarea);
            java.util.concurrent.Future<Object> b = pool.submit(tarea);
            java.util.List<Object> resultados = java.util.List.of(
                    a.get(30, java.util.concurrent.TimeUnit.SECONDS), b.get(30, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(llamadas.get()).isEqualTo(1);
            assertThat(resultados).filteredOn(r -> r instanceof ComprobanteNoReenviableException).hasSize(1);
            assertThat(resultados).filteredOn("ok"::equals).hasSize(1);
        } finally {
            liberarHacienda.countDown();
            pool.shutdownNow();
        }
        assertThat(recargar(comprobante).getReenviosManuales()).isEqualTo(1);
    }

    /**
     * Triangulación de {@link #registrarComprobanteDentroDeUnaTransaccionAsignaConsecutivoYClaveSegunElCodigoDelPerfil}:
     * ese test solo prueba {@code NOTA_CREDITO} ("03"). Un segundo tipo distinto confirma que el
     * código embebido en consecutivo/clave viene realmente de {@code perfil.getCodigo()}, no de un
     * valor hardcodeado a "03" que coincidiera por casualidad con ese único test.
     */
    @Test
    void registrarComprobanteConOtroTipoEmbebeSuPropioCodigo() {
        Cliente cliente = crearCliente();
        Factura factura = crearFacturaMinima(cliente.getId());
        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ComprobanteElectronico comprobante = transactionTemplate.execute(status ->
                comprobanteEmisionService.registrarComprobante(
                        factura, TipoComprobantePerfil.NOTA_DEBITO, empresa, ahoraUtc));

        assertThat(comprobante.getTipoComprobante()).isEqualTo("02");
        assertThat(comprobante.getConsecutivo().substring(8, 10)).isEqualTo("02");
        String segmentoConsecutivoEnClave = comprobante.getClaveNumerica().substring(21, 41);
        assertThat(segmentoConsecutivoEnClave).isEqualTo(comprobante.getConsecutivo());
    }

    @Test
    void procesarXmlYEnvioDelegaEnComprobanteXmlPersistenceService() {
        UUID comprobanteId = UUID.randomUUID();

        comprobanteEmisionService.procesarXmlYEnvio(comprobanteId);

        verify(comprobanteXmlPersistenceService).generarYPersistirXml(comprobanteId);
    }
}
