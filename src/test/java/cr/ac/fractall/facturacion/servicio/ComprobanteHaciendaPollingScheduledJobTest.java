package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.notificaciones.servicio.EmailNotificacionService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración (Postgres + Vault reales vía Testcontainers, mismo bootstrap mínimo que
 * {@code CatalogoControllerTest} -- este job no usa Vault para nada, pero CUALQUIER
 * {@code @SpringBootTest} lo necesita para arrancar, ver su javadoc) de
 * {@link ComprobanteHaciendaPollingScheduledJob}.
 *
 * <p>Esta es la prueba MÁS IMPORTANTE de la sub-tarea de sondeo: prueba que
 * {@link ComprobanteElectronicoRepository#findEmpresaIdsConEstado} (SQL nativo) realmente permite
 * DESCUBRIR más de una empresa con trabajo pendiente sin conocerlas de antemano, y que el ciclo
 * por-empresa del job (que fija {@link TenantContext} al {@code empresaId} REAL de cada iteración,
 * nunca un valor de descarte -- ver el javadoc del job) efectivamente CRUZA tenants sin filtrar ni
 * mezclar sus filas: se crean comprobantes {@code ENVIADO} para DOS empresas reales, se verifica
 * que AMBOS terminan procesados con el resultado correcto (distinto por empresa, para probar que
 * la credencial/respuesta de Hacienda usada en cada llamada corresponde a la empresa correcta) y
 * que, tras la corrida, cada empresa solo ve su propia fila vía las consultas JPQL normales
 * (filtradas por {@code @TenantId}).
 *
 * <p>{@link HaciendaComprobanteApiService} se reemplaza con {@code @MockitoBean} -- evita una
 * llamada HTTP real a Hacienda, mismo motivo que {@code CatalogoControllerTest} mockea
 * {@code HaciendaApiService}. Ninguna respuesta simulada trae {@code xmlRespuesta}, así que este
 * escenario nunca ejercita el camino de cifrado/subida a Object Storage (cubierto por separado en
 * {@code ComprobanteHaciendaEnvioServiceTest}) -- evita necesitar también un
 * {@code @MockitoBean} de {@code ObjectStorageService} aquí.
 */
@Testcontainers
@SpringBootTest
class ComprobanteHaciendaPollingScheduledJobTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();

        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);
    }

    private static void bootstrapAppRole() throws Exception {
        ejecutarVault("auth", "enable", "approle");
        ejecutarVault("secrets", "enable", "transit");
        ejecutarVault("write", "-f", "transit/keys/" + TRANSIT_KEY);

        String politicaHcl = """
                path "secret/data/empresas/*" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/keys/%s" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/datakey/plaintext/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/decrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
        ExecResult resultadoPolitica = VAULT.execInContainer(
                "sh", "-c",
                "cat <<'EOF' | vault policy write " + POLICY_NAME + " -\n" + politicaHcl + "EOF");
        assertThat(resultadoPolitica.getExitCode()).as(resultadoPolitica.getStderr()).isZero();

        ejecutarVault("write", "auth/approle/role/" + APPROLE_NAME,
                "token_policies=" + POLICY_NAME,
                "token_ttl=1h",
                "token_max_ttl=4h",
                "secret_id_ttl=0",
                "token_num_uses=0");

        roleId = ejecutarVault("read", "-field=role_id", "auth/approle/role/" + APPROLE_NAME + "/role-id")
                .getStdout().trim();
        secretId = ejecutarVault("write", "-field=secret_id", "-f", "auth/approle/role/" + APPROLE_NAME + "/secret-id")
                .getStdout().trim();
    }

    private static ExecResult ejecutarVault(String... comandoVault) throws Exception {
        String[] comandoCompleto = new String[comandoVault.length + 1];
        comandoCompleto[0] = "vault";
        System.arraycopy(comandoVault, 0, comandoCompleto, 1, comandoVault.length);

        ExecResult resultado = VAULT.execInContainer(comandoCompleto);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        return resultado;
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
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private ComprobanteHaciendaPollingScheduledJob job;

    @MockitoBean
    private HaciendaComprobanteApiService haciendaComprobanteApiService;

    // PR6: el job ahora envía un correo digest por empresa cuando escala comprobantes en un ciclo
    // (ver ComprobanteHaciendaPollingScheduledJobTest#variosComprobantesEscaladosEnUnCicloEnvianUnSoloDigest
    // y sucesivas). Mockeado a nivel de clase -- sin esto, CUALQUIER prueba que fuerce una
    // escalación (p. ej. procesarEmpresa_incrementaIntentosConsulta_enRutaFallida) intentaría un
    // envío real vía Resend e insertaría una fila en cola_reintento_email.
    @MockitoBean
    private EmailNotificacionService emailNotificacionService;

    private Empresa empresaA;
    private Empresa empresaB;
    private CredencialHacienda credencialA;
    private CredencialHacienda credencialB;

    @BeforeEach
    void setUp() {
        // UUID de descarte solo para abrir el EntityManager que crea Usuario/Empresa/
        // CredencialHacienda (ninguna de las tres es @TenantId) -- mismo patrón que
        // AislamientoMultiTenantTest#setUp().
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba sondeo Hacienda");
        usuario.setEmail("usuario-sondeo-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        empresaA = nuevaEmpresa("Empresa Sondeo A S.A.", usuario.getId());
        empresaB = nuevaEmpresa("Empresa Sondeo B S.A.", usuario.getId());
        empresaA = empresaRepository.save(empresaA);
        empresaB = empresaRepository.save(empresaB);

        credencialA = nuevaCredencial(empresaA.getId(), usuario.getId());
        credencialB = nuevaCredencial(empresaB.getId(), usuario.getId());
        credencialA = credencialHaciendaRepository.save(credencialA);
        credencialB = credencialHaciendaRepository.save(credencialB);
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
        // PR6: destinatario por defecto del digest de notificación -- las pruebas que necesitan
        // ejercitar el guard de email null/vacío construyen su propia Empresa sin este setter en
        // vez de reusar este helper.
        empresa.setEmail("digest-" + UUID.randomUUID() + "@fractall.test");
        empresa.setCreadoPor(creadoPor);
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        return empresa;
    }

    private static CredencialHacienda nuevaCredencial(UUID empresaId, UUID configuradaPor) {
        return nuevaCredencial(empresaId, configuradaPor, "SANDBOX");
    }

    /**
     * Variante con ambiente parametrizable -- necesaria para
     * {@link #comprobantesDeAmbientesDistintosDeLaMismaEmpresaSeConsultanIndependientemente()}, que
     * necesita una credencial PRODUCCION para la MISMA empresa además de la credencial SANDBOX que
     * ya provee {@code credencialA} ({@link CredencialHaciendaRepository#findByEmpresaIdAndAmbiente}
     * exige una fila por cada combinación empresa+ambiente).
     */
    private static CredencialHacienda nuevaCredencial(UUID empresaId, UUID configuradaPor, String ambiente) {
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresaId);
        credencial.setAmbiente(ambiente);
        credencial.setUsuarioHacienda("usuario-" + empresaId + "-" + ambiente + "@hacienda.test");
        credencial.setCredencialReferencia(
                "secret/data/empresas/" + empresaId + "/hacienda/" + ambiente.toLowerCase() + "/password");
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(configuradaPor);
        return credencial;
    }

    /**
     * Crea un {@code ComprobanteElectronico} en {@code ENVIADO} para la empresa actualmente en
     * {@link TenantContext} -- el llamador es responsable de fijarlo antes de invocar esto, igual
     * que {@code AislamientoMultiTenantTest}.
     */
    private ComprobanteElectronico nuevoComprobanteEnviado(
            UUID empresaId, UUID usuarioId, String sufijoClave, int intentosEnvio, LocalDateTime fechaRespuesta) {
        return nuevoComprobanteEnviado(empresaId, usuarioId, sufijoClave, intentosEnvio, fechaRespuesta, "SANDBOX");
    }

    /**
     * Variante con {@code ambienteHacienda} parametrizable -- necesaria para
     * {@link #comprobantesDeAmbientesDistintosDeLaMismaEmpresaSeConsultanIndependientemente()}, que
     * exige comprobantes PRODUCCION además de los SANDBOX que todo el resto de esta clase asume por
     * defecto.
     */
    private ComprobanteElectronico nuevoComprobanteEnviado(
            UUID empresaId, UUID usuarioId, String sufijoClave, int intentosEnvio, LocalDateTime fechaRespuesta,
            String ambienteHacienda) {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente sondeo " + sufijoClave);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        cliente = clienteRepository.save(cliente);

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(BigDecimal.ONE);
        factura.setSubtotal(new BigDecimal("1000.00000"));
        factura.setTotalImpuesto(new BigDecimal("130.00000"));
        factura.setTotal(new BigDecimal("1130.00000"));
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura = facturaRepository.saveAndFlush(factura);

        String consecutivo = (sufijoClave + "00000000000000000000").substring(0, 20);
        String claveNumerica = (sufijoClave + "0".repeat(50)).substring(0, 50);

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda(ambienteHacienda);
        comprobante.setTipoComprobante("01");
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("ENVIADO");
        comprobante.setIntentosEnvio(intentosEnvio);
        comprobante.setFechaEmision(LocalDateTime.now());
        comprobante.setFechaRespuesta(fechaRespuesta);
        return comprobanteElectronicoRepository.saveAndFlush(comprobante);
    }

    private static RespuestaHaciendaDTO respuesta(MensajeHacienda mensaje, boolean exitoso, boolean debeReintentar) {
        return RespuestaHaciendaDTO.builder()
                .fechaRespuesta(LocalDateTime.now())
                .codigoMensaje(mensaje)
                .mensaje("respuesta de prueba")
                .exitoso(exitoso)
                .debeReintentar(debeReintentar)
                .codigoHttp(200)
                .build();
    }

    @Test
    void procesaComprobantesPendientesDeDosEmpresasSinCruzarNiMezclarSusFilas() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobanteA = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEA", 1, LocalDateTime.now().minusHours(1));

        TenantContext.set(empresaB.getId());
        ComprobanteElectronico comprobanteB = nuevoComprobanteEnviado(
                empresaB.getId(), empresaB.getCreadoPor(), "CLAVEB", 1, LocalDateTime.now().minusHours(1));

        // Distinto resultado por empresa a propósito: si el job mezclara tenants o usara la
        // credencial equivocada, alguno de los dos asserts de estado más abajo fallaría.
        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteA.getClaveNumerica()), eq(credencialA.getId())))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));
        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteB.getClaveNumerica()), eq(credencialB.getId())))
                .thenReturn(respuesta(MensajeHacienda.RECHAZADO, false, false));

        // El job corre fuera de cualquier contexto de tenant ya resuelto, igual que un
        // @Scheduled real -- ver su javadoc (usa TenantContextDescartable solo para la consulta
        // nativa de descubrimiento, y fija el tenant REAL para el trabajo por empresa).
        TenantContext.clear();

        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargadoA = comprobanteElectronicoRepository.findById(comprobanteA.getId())
                .orElseThrow();
        assertThat(recargadoA.getEstado()).isEqualTo("ACEPTADO");
        // Prueba de aislamiento real (no solo que compila): en contexto de empresa A, la
        // consulta JPQL normal jamás debe devolver la fila de empresa B.
        assertThat(comprobanteElectronicoRepository.findAll())
                .extracting(ComprobanteElectronico::getId)
                .containsExactly(comprobanteA.getId());

        TenantContext.set(empresaB.getId());
        ComprobanteElectronico recargadoB = comprobanteElectronicoRepository.findById(comprobanteB.getId())
                .orElseThrow();
        assertThat(recargadoB.getEstado()).isEqualTo("RECHAZADO");
        assertThat(comprobanteElectronicoRepository.findAll())
                .extracting(ComprobanteElectronico::getId)
                .containsExactly(comprobanteB.getId());

        verify(haciendaComprobanteApiService)
                .consultarComprobante(comprobanteA.getClaveNumerica(), credencialA.getId());
        verify(haciendaComprobanteApiService)
                .consultarComprobante(comprobanteB.getClaveNumerica(), credencialB.getId());
    }

    @Test
    void comprobanteQueAgotaLosIntentosMaximosPasaAEstadoError() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEMAX",
                ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS - 1,
                LocalDateTime.now().minusHours(3));

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargado.getIntentosEnvio()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS);
    }

    /**
     * Hallazgo de revisión (PR3): {@code CredencialHaciendaNoEncontradaException} ahora extiende
     * {@link cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException} -- una credencial
     * ausente es, por definición, una falla de configuración que ningún reintento automático puede
     * resolver. Por eso ya NO comparte el presupuesto de {@code MAX_INTENTOS} de las fallas de
     * comunicación: el job debe escalar a {@code ERROR} en el PRIMER intento, sin llegar a llamar a
     * Hacienda para los intentos restantes.
     *
     * <p>Reemplaza a la prueba anterior
     * ({@code comprobanteQueFallaPorCredencialFaltanteCuentaComoIntentoYEscalaAError}), que asumía
     * el tope indiferenciado de 10 intentos y esperaba {@code ultimoResultadoConsulta =
     * ERROR_COMUNICACION} -- ambas expectativas ya no reflejan el comportamiento correcto una vez
     * que el job distingue por causa.
     */
    @Test
    void comprobanteQueFallaPorCredencialFaltanteEscalaAErrorEnUnSoloIntento() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVENOCRED", 0, null);
        credencialHaciendaRepository.delete(credencialA);

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargado.getUltimoResultadoConsulta())
                .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);
        assertThat(recargado.getIntentosEnvio()).isEqualTo(1);
        assertThat(recargado.getFechaUltimaConsultaHacienda()).isNotNull();
        verifyNoInteractions(haciendaComprobanteApiService);
    }

    /**
     * Contraparte de la prueba anterior: una falla de COMUNICACIÓN (p. ej. cualquier otra
     * {@link RuntimeException} no clasificada como configuración) debe seguir consumiendo el
     * presupuesto de {@code MAX_INTENTOS} con el backoff existente, exactamente igual que antes de
     * distinguir por causa -- esta prueba fija ese comportamiento como el "camino no tocado" de la
     * rama de comunicación.
     */
    @Test
    void comprobanteQueFallaPorComunicacionSigueAgotandoLosDiezIntentosConBackoff() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECOMU",
                ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS - 1,
                LocalDateTime.now().minusHours(3));

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaComunicacionException("timeout de prueba"));

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargado.getIntentosEnvio()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS);
        assertThat(recargado.getUltimoResultadoConsulta())
                .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_COMUNICACION);
        assertThat(recargado.getFechaUltimaConsultaHacienda()).isNotNull();
    }

    /**
     * PR6: corte por credencial dentro de un mismo ciclo. Tres comprobantes pendientes de la MISMA
     * empresa+ambiente comparten una credencial rota (en este caso, un 401 persistente simulado
     * lanzando {@code HaciendaConfiguracionException} directamente desde el mock de la API, para
     * probar el corte cuando SÍ se llega a golpear a Hacienda -- a diferencia de la credencial
     * ausente, que nunca llega a la API). Solo el PRIMER comprobante debe disparar una llamada real
     * a Hacienda; los otros dos deben escalarse a {@code ERROR}/{@code ERROR_CONFIGURACION} sin
     * volver a invocar {@code consultarComprobante}.
     */
    @Test
    void variosComprobantesDeLaMismaCredencialRotaSoloConsultanHaciendaUnaVez() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante1 = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECORTE1", 0, null);
        ComprobanteElectronico comprobante2 = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECORTE2", 0, null);
        ComprobanteElectronico comprobante3 = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECORTE3", 0, null);

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException(
                        "401 persistente de prueba"));

        TenantContext.clear();
        job.consultarPendientes();

        verify(haciendaComprobanteApiService, times(1)).consultarComprobante(any(), any());

        TenantContext.set(empresaA.getId());
        for (ComprobanteElectronico original : List.of(comprobante1, comprobante2, comprobante3)) {
            ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(original.getId())
                    .orElseThrow();
            assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
            assertThat(recargado.getUltimoResultadoConsulta())
                    .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);
        }
    }

    /**
     * Contraparte de la prueba anterior: fallas de COMUNICACIÓN NO activan el corte por credencial
     * -- cada comprobante de la misma empresa+ambiente se sigue consultando de forma independiente.
     */
    @Test
    void variosComprobantesFallandoPorComunicacionSeConsultanCadaUnoIndependientemente() {
        TenantContext.set(empresaA.getId());
        nuevoComprobanteEnviado(empresaA.getId(), empresaA.getCreadoPor(), "CLAVECOMU1", 0, null);
        nuevoComprobanteEnviado(empresaA.getId(), empresaA.getCreadoPor(), "CLAVECOMU2", 0, null);
        nuevoComprobanteEnviado(empresaA.getId(), empresaA.getCreadoPor(), "CLAVECOMU3", 0, null);

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaComunicacionException(
                        "timeout de prueba"));

        TenantContext.clear();
        job.consultarPendientes();

        verify(haciendaComprobanteApiService, times(3)).consultarComprobante(any(), any());
    }

    /**
     * Cierra el WARNING 2 de sdd-verify (id de sesión previa): el corte por credencial rota
     * ({@code ambientesConFalloConfiguracion} dentro de {@code procesarEmpresa}) está keyed por
     * {@code ambienteHacienda}, no por empresa entera. Todos los demás fixtures de esta clase
     * hardcodean SANDBOX, así que ninguna prueba anterior ejercitaba dos ambientes distintos de la
     * MISMA empresa en el mismo ciclo -- esta prueba fuerza el fallo de configuración SOLO en
     * SANDBOX y comprueba que (a) el comprobante PRODUCCION de la misma empresa recibe su llamada
     * normal a Hacienda (no se corta) y (b) un segundo comprobante SANDBOX SÍ se corta sin volver a
     * llamar a Hacienda -- confirmando que el Set es independiente por ambiente y no se comparte
     * entre ambientes de la misma empresa.
     */
    @Test
    void comprobantesDeAmbientesDistintosDeLaMismaEmpresaSeConsultanIndependientemente() {
        TenantContext.set(empresaA.getId());
        CredencialHacienda credencialProduccionA = credencialHaciendaRepository.save(
                nuevaCredencial(empresaA.getId(), empresaA.getCreadoPor(), "PRODUCCION"));

        ComprobanteElectronico comprobanteSandbox1 = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEAMB1", 0, null, "SANDBOX");
        ComprobanteElectronico comprobanteSandbox2 = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEAMB2", 0, null, "SANDBOX");
        ComprobanteElectronico comprobanteProduccion = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEAMB3", 0, null, "PRODUCCION");

        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteSandbox1.getClaveNumerica()), eq(credencialA.getId())))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException(
                        "401 persistente de prueba en SANDBOX"));
        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteProduccion.getClaveNumerica()), eq(credencialProduccionA.getId())))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        TenantContext.clear();
        job.consultarPendientes();

        // El comprobante PRODUCCION SÍ recibe su llamada normal -- el fallo de configuración de
        // SANDBOX no lo corta.
        verify(haciendaComprobanteApiService, times(1)).consultarComprobante(
                comprobanteProduccion.getClaveNumerica(), credencialProduccionA.getId());
        // Solo el PRIMER comprobante SANDBOX golpea Hacienda...
        verify(haciendaComprobanteApiService, times(1))
                .consultarComprobante(comprobanteSandbox1.getClaveNumerica(), credencialA.getId());
        // ...el segundo SANDBOX se corta sin volver a llamar a Hacienda.
        verify(haciendaComprobanteApiService, never())
                .consultarComprobante(eq(comprobanteSandbox2.getClaveNumerica()), any());
        // Total: exactamente 2 llamadas reales (1 SANDBOX + 1 PRODUCCION), nunca 3.
        verify(haciendaComprobanteApiService, times(2)).consultarComprobante(any(), any());

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargadoSandbox1 = comprobanteElectronicoRepository
                .findById(comprobanteSandbox1.getId()).orElseThrow();
        assertThat(recargadoSandbox1.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargadoSandbox1.getUltimoResultadoConsulta())
                .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);

        ComprobanteElectronico recargadoSandbox2 = comprobanteElectronicoRepository
                .findById(comprobanteSandbox2.getId()).orElseThrow();
        assertThat(recargadoSandbox2.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargadoSandbox2.getUltimoResultadoConsulta())
                .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);

        ComprobanteElectronico recargadoProduccion = comprobanteElectronicoRepository
                .findById(comprobanteProduccion.getId()).orElseThrow();
        assertThat(recargadoProduccion.getEstado()).isEqualTo("ACEPTADO");
    }

    @Test
    void comprobanteDentroDeLaVentanaDeBackoffNoSeConsultaTodavia() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEBO", 1, LocalDateTime.now());

        TenantContext.clear();
        job.consultarPendientes();

        verifyNoInteractions(haciendaComprobanteApiService);

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo("ENVIADO");
        assertThat(recargado.getIntentosEnvio()).isEqualTo(1);
    }

    // ========== T-B1: intentosConsulta increment ==========

    @Test
    void procesarEmpresa_incrementaIntentosConsulta_enRutaExitosa() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECNT1",
                0, LocalDateTime.now().minusHours(1));
        int intentosConsultaAntes = comprobante.getIntentosConsulta();

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository
                .findById(comprobante.getId()).orElseThrow();
        assertThat(recargado.getIntentosConsulta()).isEqualTo(intentosConsultaAntes + 1);
    }

    @Test
    void procesarEmpresa_incrementaIntentosConsulta_enRutaFallida() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVECNT2",
                0, LocalDateTime.now().minusHours(1));
        int intentosConsultaAntes = comprobante.getIntentosConsulta();

        // Deleting the credencial forces consultarYActualizar to throw before reaching Hacienda,
        // exercising the catch/registrarIntentoFallidoYGuardar path.
        credencialHaciendaRepository.delete(credencialA);

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository
                .findById(comprobante.getId()).orElseThrow();
        assertThat(recargado.getIntentosConsulta()).isEqualTo(intentosConsultaAntes + 1);
    }

    // ========== PR6: digest de notificación por empresa ==========

    /**
     * Un ciclo que escala más de un comprobante (causas mixtas: comunicación agotando sus 10
     * intentos, configuración en un solo intento) para la MISMA empresa debe disparar EXACTAMENTE
     * un correo digest resumiendo todos los escalados de ese ciclo, no uno por comprobante.
     */
    @Test
    void variosComprobantesEscaladosEnUnCicloEnvianUnSoloDigest() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobanteComunicacion = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEDIGCOMU",
                ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS - 1,
                LocalDateTime.now().minusHours(3));
        ComprobanteElectronico comprobanteConfiguracion = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEDIGCONF", 0, null);

        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteComunicacion.getClaveNumerica()), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaComunicacionException(
                        "timeout de prueba"));
        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteConfiguracion.getClaveNumerica()), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException(
                        "401 persistente de prueba"));

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        assertThat(comprobanteElectronicoRepository.findById(comprobanteComunicacion.getId()).orElseThrow()
                .getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(comprobanteElectronicoRepository.findById(comprobanteConfiguracion.getId()).orElseThrow()
                .getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);

        ArgumentCaptor<String> asuntoCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cuerpoCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificacionService, times(1)).enviarConReintento(
                eq(empresaA.getEmail()), asuntoCaptor.capture(), cuerpoCaptor.capture());

        assertThat(asuntoCaptor.getValue()).contains("2");
        assertThat(cuerpoCaptor.getValue()).contains(comprobanteComunicacion.getClaveNumerica());
        assertThat(cuerpoCaptor.getValue()).contains(comprobanteConfiguracion.getClaveNumerica());
    }

    /**
     * Un ciclo sin escalaciones NUEVAS (la única consulta del ciclo termina en éxito) no debe
     * disparar ningún correo digest.
     */
    @Test
    void sinEscaladosNuevosEnElCicloNoSeEnviaDigest() {
        TenantContext.set(empresaA.getId());
        nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEDIGOK", 0, LocalDateTime.now().minusHours(1));

        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        TenantContext.clear();
        job.consultarPendientes();

        verifyNoInteractions(emailNotificacionService);
    }

    /**
     * Comprobantes escalados de DOS empresas distintas en el mismo ciclo del scheduler no deben
     * mezclarse en un único digest -- cada empresa recibe su propio correo, dirigido a su propio
     * {@code Empresa.email}, listando solo sus propios comprobantes.
     */
    @Test
    void comprobantesDeDistintasEmpresasNoSeMezclanEnElMismoDigest() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobanteA = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVEDIGA", 0, null);
        credencialHaciendaRepository.delete(credencialA);

        TenantContext.set(empresaB.getId());
        ComprobanteElectronico comprobanteB = nuevoComprobanteEnviado(
                empresaB.getId(), empresaB.getCreadoPor(), "CLAVEDIGB",
                ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS - 1,
                LocalDateTime.now().minusHours(3));
        when(haciendaComprobanteApiService.consultarComprobante(
                eq(comprobanteB.getClaveNumerica()), any()))
                .thenThrow(new cr.ac.fractall.hacienda.servicio.HaciendaComunicacionException(
                        "timeout de prueba"));

        TenantContext.clear();
        job.consultarPendientes();

        ArgumentCaptor<String> destinatarioCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cuerpoCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailNotificacionService, times(2)).enviarConReintento(
                destinatarioCaptor.capture(), any(), cuerpoCaptor.capture());

        assertThat(destinatarioCaptor.getAllValues()).containsExactlyInAnyOrder(
                empresaA.getEmail(), empresaB.getEmail());

        int indiceA = destinatarioCaptor.getAllValues().indexOf(empresaA.getEmail());
        int indiceB = destinatarioCaptor.getAllValues().indexOf(empresaB.getEmail());
        assertThat(cuerpoCaptor.getAllValues().get(indiceA))
                .contains(comprobanteA.getClaveNumerica())
                .doesNotContain(comprobanteB.getClaveNumerica());
        assertThat(cuerpoCaptor.getAllValues().get(indiceB))
                .contains(comprobanteB.getClaveNumerica())
                .doesNotContain(comprobanteA.getClaveNumerica());
    }

    /**
     * Guard: si {@code Empresa.email} es null/vacío, el job NO debe intentar enviar el digest (ni
     * lanzar una excepción por ello) -- solo loguear un WARN. La escalación en sí (el efecto
     * principal del ciclo) debe seguir ocurriendo con normalidad.
     */
    @Test
    void empresaSinEmailNoIntentaEnviarDigest() {
        TenantContext.set(UUID.randomUUID());
        Usuario usuario = usuarioRepository.findAll().get(0);
        Empresa empresaSinEmail = new Empresa();
        empresaSinEmail.setRazonSocial("Empresa Sondeo Sin Email S.A.");
        empresaSinEmail.setAmbienteHacienda("SANDBOX");
        empresaSinEmail.setStatus("REGISTRADA");
        empresaSinEmail.setCreadoPor(usuario.getId());
        empresaSinEmail.setCreateDate(LocalDateTime.now());
        empresaSinEmail.setUpdateDate(LocalDateTime.now());
        empresaSinEmail = empresaRepository.save(empresaSinEmail);
        CredencialHacienda credencialSinEmail = nuevaCredencial(empresaSinEmail.getId(), usuario.getId());
        credencialHaciendaRepository.save(credencialSinEmail);

        TenantContext.set(empresaSinEmail.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaSinEmail.getId(), usuario.getId(), "CLAVEDIGSINMAIL", 0, null);
        credencialHaciendaRepository.delete(credencialSinEmail);

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaSinEmail.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargado.getUltimoResultadoConsulta())
                .isEqualTo(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);

        verifyNoInteractions(emailNotificacionService);
    }
}
