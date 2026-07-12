package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        empresa.setCreadoPor(creadoPor);
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        return empresa;
    }

    private static CredencialHacienda nuevaCredencial(UUID empresaId, UUID configuradaPor) {
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresaId);
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda("usuario-" + empresaId + "@hacienda.test");
        credencial.setCredencialReferencia("secret/data/empresas/" + empresaId + "/hacienda/sandbox/password");
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
        factura = facturaRepository.saveAndFlush(factura);

        String consecutivo = (sufijoClave + "00000000000000000000").substring(0, 20);
        String claveNumerica = (sufijoClave + "0".repeat(50)).substring(0, 50);

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda("SANDBOX");
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
     * Hallazgo de revisión: si {@code consultarYActualizar} lanza ANTES de tocar
     * {@code intentosEnvio} (p. ej. la credencial de Hacienda fue borrada mientras el comprobante
     * esperaba en {@code ENVIADO}), el intento fallido debe contar igual para el tope de
     * {@code MAX_INTENTOS} -- si no, el comprobante queda reintentando para siempre sin escalar
     * nunca a {@code ERROR}.
     */
    @Test
    void comprobanteQueFallaPorCredencialFaltanteCuentaComoIntentoYEscalaAError() {
        TenantContext.set(empresaA.getId());
        ComprobanteElectronico comprobante = nuevoComprobanteEnviado(
                empresaA.getId(), empresaA.getCreadoPor(), "CLAVENOCRED",
                ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS - 1,
                LocalDateTime.now().minusHours(3));
        credencialHaciendaRepository.delete(credencialA);

        TenantContext.clear();
        job.consultarPendientes();

        TenantContext.set(empresaA.getId());
        ComprobanteElectronico recargado = comprobanteElectronicoRepository.findById(comprobante.getId())
                .orElseThrow();
        assertThat(recargado.getEstado()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.ESTADO_ERROR);
        assertThat(recargado.getIntentosEnvio()).isEqualTo(ComprobanteHaciendaPollingScheduledJob.MAX_INTENTOS);
        verifyNoInteractions(haciendaComprobanteApiService);
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
}
