package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.MensajeHaciendaDTO;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.dto.TokenHaciendaDTO;
import cr.ac.fractall.secretos.SecretosKvService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba unitaria (sin contexto de Spring) de {@link HaciendaComprobanteApiServiceImpl} -- mismo
 * enfoque que {@link HaciendaConsultaServiceImplTest} (constructor de paquete con un
 * {@link RestClient} ya enlazado a {@link MockRestServiceServer}), con {@code self} fijado a
 * {@code this} por ese constructor -- ver su javadoc y el de la clase bajo prueba sobre por qué
 * eso es seguro aquí (sin proxy de Spring, no hay auto-invocación que sortear). El aislamiento
 * del CACHÉ de token entre dos credenciales/empresas distintas -- que SÍ depende de un proxy real
 * de Spring -- se prueba por separado en {@code HaciendaTokenCacheAislamientoTest}.
 */
class HaciendaComprobanteApiServiceImplTest {

    private static final String SANDBOX_TOKEN_URL = "https://idp.test/token";
    private static final String SANDBOX_API_URL = "https://api-sandbox.test";
    private static final String PRODUCCION_TOKEN_URL = "https://idp.test/token-prod";
    private static final String PRODUCCION_API_URL = "https://api.test";

    private MockRestServiceServer servidorMock;
    private CredencialHaciendaRepository credencialHaciendaRepository;
    private EmpresaRepository empresaRepository;
    private SecretosKvService secretosKvService;
    private HaciendaComprobanteApiServiceImpl servicio;

    @BeforeEach
    void configurar() {
        RestClient.Builder builder = RestClient.builder();
        servidorMock = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        credencialHaciendaRepository = mock(CredencialHaciendaRepository.class);
        empresaRepository = mock(EmpresaRepository.class);
        secretosKvService = mock(SecretosKvService.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();

        servicio = new HaciendaComprobanteApiServiceImpl(
                credencialHaciendaRepository,
                empresaRepository,
                secretosKvService,
                restClient,
                objectMapper,
                "api-stag",
                SANDBOX_TOKEN_URL,
                SANDBOX_API_URL,
                PRODUCCION_TOKEN_URL,
                PRODUCCION_API_URL);
    }

    private static CredencialHacienda credencialSandbox(UUID id, UUID empresaId) {
        CredencialHacienda credencial = new CredencialHacienda();
        credencial.setEmpresaId(empresaId);
        credencial.setAmbiente("SANDBOX");
        credencial.setUsuarioHacienda("usuario@hacienda.test");
        credencial.setCredencialReferencia("secret/data/empresas/" + empresaId + "/hacienda/sandbox/password");
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(UUID.randomUUID());
        return credencial;
    }

    private static Empresa empresaConIdentificacion(String numero, String tipo) {
        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa de prueba S.A.");
        empresa.setNumeroIdentificacion(numero);
        empresa.setTipoIdentificacion(tipo);
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("HABILITADA");
        return empresa;
    }

    // ========== autenticar ==========

    @Test
    void autenticarConCredencialValidaDevuelveTokenParseadoDeHacienda() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString("grant_type=password")))
                .andExpect(content().string(Matchers.containsString("username=usuario%40hacienda.test")))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT-123","refresh_token":"RT-123","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));

        TokenHaciendaDTO token = servicio.autenticar(credencialId);

        assertThat(token.accessToken()).isEqualTo("AT-123");
        assertThat(token.refreshToken()).isEqualTo("RT-123");
        assertThat(token.expiresIn()).isEqualTo(3600);
        assertThat(token.tokenType()).isEqualTo("Bearer");
        servidorMock.verify();
    }

    @Test
    void autenticarConCredencialInexistenteLanzaExcepcion() {
        UUID credencialId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.autenticar(credencialId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(credencialId.toString());
    }

    @Test
    void autenticarSinPasswordEnVaultLanzaExcepcionYNoLlamaAHacienda() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.autenticar(credencialId))
                .isInstanceOf(IllegalStateException.class);
        servidorMock.verify();
    }

    // ========== renovarToken ==========

    @Test
    void renovarTokenExitosoNoReautentica() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("grant_type=refresh_token")))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT-nuevo","refresh_token":"RT-nuevo","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));

        TokenHaciendaDTO token = servicio.renovarToken("RT-viejo", credencialId);

        assertThat(token.accessToken()).isEqualTo("AT-nuevo");
        servidorMock.verify();
    }

    @Test
    void renovarTokenFallidoReintentaAutenticacionCompleta() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("grant_type=refresh_token")))
                .andRespond(withServerError());
        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("grant_type=password")))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT-reautenticado","refresh_token":"RT-x","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));

        TokenHaciendaDTO token = servicio.renovarToken("RT-invalido", credencialId);

        assertThat(token.accessToken()).isEqualTo("AT-reautenticado");
        servidorMock.verify();
    }

    // ========== enviarComprobante ==========

    @Test
    void enviarComprobanteAceptadoDevuelveExitosoConXmlDeRespuesta() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(empresaRepository.findById(empresaId))
                .thenReturn(Optional.of(empresaConIdentificacion("310123456", "02")));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT-envio","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_API_URL + "/recepcion/v1/recepcion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(
                        """
                        {"ind-estado":"aceptado","mensaje":"Comprobante aceptado","respuesta-xml":"PHhtbC8+"}
                        """,
                        MediaType.APPLICATION_JSON));

        RespuestaHaciendaDTO respuesta = servicio.enviarComprobante("<xml/>", "50601011900...", credencialId);

        assertThat(respuesta.getExitoso()).isTrue();
        assertThat(respuesta.getCodigoMensaje()).isEqualTo(MensajeHacienda.ACEPTADO);
        assertThat(respuesta.getXmlRespuesta()).isEqualTo("PHhtbC8+");
        servidorMock.verify();
    }

    @Test
    void enviarComprobanteConRespuesta202VaciaQuedaEnProcesamiento() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(empresaRepository.findById(empresaId))
                .thenReturn(Optional.of(empresaConIdentificacion("310123456", "02")));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_API_URL + "/recepcion/v1/recepcion"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        RespuestaHaciendaDTO respuesta = servicio.enviarComprobante("<xml/>", "clave-x", credencialId);

        assertThat(respuesta.getExitoso()).isFalse();
        assertThat(respuesta.getCodigoMensaje()).isEqualTo(MensajeHacienda.PROCESANDO);
        assertThat(respuesta.getDebeReintentar()).isFalse();
        servidorMock.verify();
    }

    // ========== consultarComprobante ==========

    @Test
    void consultarComprobanteNoEncontradoDevuelveProcesandoConReintento() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_API_URL + "/recepcion/v1/recepcion/clave-404"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        RespuestaHaciendaDTO respuesta = servicio.consultarComprobante("clave-404", credencialId);

        assertThat(respuesta.getCodigoMensaje()).isEqualTo(MensajeHacienda.PROCESANDO);
        assertThat(respuesta.getCodigoHttp()).isEqualTo(404);
        assertThat(respuesta.getDebeReintentar()).isTrue();
        servidorMock.verify();
    }

    // ========== consultarMensajes ==========

    @Test
    void consultarMensajesParseaListaDesdeRespuestaDeHacienda() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(empresaRepository.findById(empresaId))
                .thenReturn(Optional.of(empresaConIdentificacion("310123456", "02")));
        when(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_API_URL + "/mensajes-receptor/v1/mensajes/310123456"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"mensajes":[{"clave":"clave-1","mensaje":"Aceptado","detalle":"","estado":"1"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        List<MensajeHaciendaDTO> mensajes = servicio.consultarMensajes(credencialId);

        assertThat(mensajes).hasSize(1);
        assertThat(mensajes.get(0).claveNumerica()).isEqualTo("clave-1");
        assertThat(mensajes.get(0).estado()).isEqualTo("1");
        servidorMock.verify();
    }

    @Test
    void consultarMensajesAnteErrorDeHaciendaDevuelveListaVacia() {
        UUID credencialId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(credencialHaciendaRepository.findById(credencialId))
                .thenReturn(Optional.of(credencialSandbox(credencialId, empresaId)));
        when(empresaRepository.findById(empresaId))
                .thenReturn(Optional.of(empresaConIdentificacion("310123456", "02")));
        when(secretosKvService.leerSecreto(any(), any()))
                .thenReturn(Optional.of("clave-secreta"));

        servidorMock.expect(requestTo(SANDBOX_TOKEN_URL))
                .andRespond(withSuccess(
                        """
                        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}
                        """,
                        MediaType.APPLICATION_JSON));
        servidorMock.expect(requestTo(SANDBOX_API_URL + "/mensajes-receptor/v1/mensajes/310123456"))
                .andRespond(withServerError());

        List<MensajeHaciendaDTO> mensajes = servicio.consultarMensajes(credencialId);

        assertThat(mensajes).isEmpty();
    }
}
