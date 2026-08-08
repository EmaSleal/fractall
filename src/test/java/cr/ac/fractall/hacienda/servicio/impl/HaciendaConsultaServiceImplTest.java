package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.modelo.Cabys;
import cr.ac.fractall.hacienda.modelo.CabysConsultaLog;
import cr.ac.fractall.hacienda.repositorio.CabysConsultaLogRepository;
import cr.ac.fractall.hacienda.repositorio.CabysRepository;

/**
 * Prueba unitaria (sin contexto de Spring) de {@link HaciendaConsultaServiceImpl} -- ver el
 * javadoc de la clase sobre por qué el constructor "de producción" acepta {@link RestClient.Builder}
 * y valores de configuración explícitos.
 *
 * <p>Estas pruebas de interceptación HTTP usan el constructor de paquete que recibe un
 * {@link RestClient} ya construido (en vez del constructor de 4 argumentos que Spring usa en
 * producción): ese otro constructor fija su PROPIO {@code ClientHttpRequestFactory} con los
 * timeouts de {@code application.hacienda.timeout} (ver {@code construirRequestFactory}), lo que
 * pisaría el factory que {@link MockRestServiceServer#bindTo(RestClient.Builder)} ya le hubiera
 * fijado al builder -- por eso el mock se enlaza al builder ANTES de construir el
 * {@code RestClient} manualmente, sin pasar nunca por el constructor de producción.
 *
 * <p>{@link CabysRepository} y {@link CabysConsultaLogRepository} se mockean con Mockito (no hay
 * contexto de Spring en esta clase) para probar el cache read-through agregado sobre
 * {@code buscarCabysPorCodigo} y el logueo agregado sobre ambos métodos de búsqueda CABYS -- ver
 * el javadoc de {@code HaciendaConsultaServiceImpl} para el diseño completo.
 */
@ExtendWith(MockitoExtension.class)
class HaciendaConsultaServiceImplTest {

    private static final String CABYS_URL = "https://api.hacienda.go.cr/fe/cabys";

    @Mock
    private CabysRepository cabysRepository;

    @Mock
    private CabysConsultaLogRepository cabysConsultaLogRepository;

    private MockRestServiceServer servidorMock;
    private HaciendaConsultaServiceImpl servicio;

    @BeforeEach
    void configurar() {
        RestClient.Builder builder = RestClient.builder();
        servidorMock = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        servicio = new HaciendaConsultaServiceImpl(restClient, "https://api.hacienda.go.cr/fe/ae", CABYS_URL,
                cabysRepository, cabysConsultaLogRepository);
    }

    @Test
    void buscarCabysDevuelveResultadosCuandoHaciendaResponde200() {
        String cuerpo = """
                {
                  "total": 1,
                  "cantidad": 1,
                  "cabys": [
                    {"codigo": "2132100000100", "descripcion": "Jugo de tomate concentrado", "impuesto": 13}
                  ]
                }
                """;
        servidorMock.expect(requestTo(startsWith(CABYS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));

        CabysBusquedaDTO resultado = servicio.buscarCabys("jugo de tomate", 10);

        assertThat(resultado.getExitosa()).isTrue();
        assertThat(resultado.tieneResultados()).isTrue();
        assertThat(resultado.getPrimerResultado().getCodigo()).isEqualTo("2132100000100");
        assertThat(resultado.getPrimerResultado().getImpuesto()).isEqualTo(13);
    }

    @Test
    void buscarCabysConTerminoVacioNoLlamaAHaciendaYRetornaFallo() {
        CabysBusquedaDTO resultado = servicio.buscarCabys("  ", 10);

        assertThat(resultado.getExitosa()).isFalse();
        assertThat(resultado.tieneResultados()).isFalse();
        servidorMock.verify();
    }

    /**
     * {@code buscarCabys} (búsqueda por texto) NUNCA se sirve desde cache -- pedido explícito
     * del usuario: la búsqueda por texto necesita el motor de relevancia de Hacienda. Cada
     * resultado exitoso sí se loguea en {@code cabys_consulta_log} para que
     * {@code CabysReconciliacionJob} lo resuelva contra el cache después.
     *
     * <p>Una de las categorías trae un "|" literal a propósito -- prueba que el separador real
     * (U+001F, no "|") no se confunde con contenido legítimo de la categoría.
     */
    @Test
    void buscarCabysPersisteLogParaCadaResultadoExitoso() {
        String cuerpo = """
                {
                  "total": 1,
                  "cantidad": 1,
                  "cabys": [
                    {"codigo": "2132100000100", "descripcion": "Jugo de tomate concentrado",
                     "categorias": ["Alimentos", "Bebidas | Refrescos", "Jugos"], "impuesto": 13,
                     "uri": "https://api.hacienda.go.cr/fe/cabys/2132100000100", "estado": "activo"}
                  ]
                }
                """;
        servidorMock.expect(requestTo(startsWith(CABYS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));

        servicio.buscarCabys("jugo de tomate", 10);

        ArgumentCaptor<CabysConsultaLog> captor = ArgumentCaptor.forClass(CabysConsultaLog.class);
        verify(cabysConsultaLogRepository, times(1)).save(captor.capture());
        CabysConsultaLog logGuardado = captor.getValue();
        assertThat(logGuardado.getCodigo()).isEqualTo("2132100000100");
        assertThat(logGuardado.getDescripcion()).isEqualTo("Jugo de tomate concentrado");
        assertThat(logGuardado.getCategorias()).isEqualTo("AlimentosBebidas | RefrescosJugos");
        assertThat(logGuardado.getImpuesto()).isEqualTo((short) 13);
        assertThat(logGuardado.getUri()).isEqualTo("https://api.hacienda.go.cr/fe/cabys/2132100000100");
        assertThat(logGuardado.getEstado()).isEqualTo("activo");
        assertThat(logGuardado.isProcesado()).isFalse();
        assertThat(logGuardado.getConsultadoEn()).isNotNull();
        // buscarCabys jamás lee el cache -- ver el javadoc de este test.
        verifyNoInteractions(cabysRepository);
    }

    /**
     * Cache hit de {@code buscarCabysPorCodigo}: el código YA vive en {@code cabys}, así que la
     * respuesta se reconstruye desde ahí sin tocar la API de Hacienda -- {@code servidorMock} no
     * tiene ninguna expectativa registrada, así que una llamada HTTP real haría fallar
     * {@link MockRestServiceServer#verify()} (o antes, con "no further requests expected").
     */
    @Test
    void buscarCabysPorCodigoSirveDesdeCacheSinLlamarAHacienda() {
        Cabys cabys = new Cabys();
        cabys.setCodigo("2132100000100");
        cabys.setDescripcion("Jugo de tomate concentrado");
        // Una categoría trae un "|" literal a propósito -- prueba que dividirCategorias separa
        // por U+001F, no por "|", así que el "|" de "Bebidas | Refrescos" no la parte en dos.
        cabys.setCategorias("AlimentosBebidas | RefrescosJugos");
        cabys.setImpuesto((short) 13);
        cabys.setUri("https://api.hacienda.go.cr/fe/cabys/2132100000100");
        cabys.setEstado("activo");
        cabys.setActualizadoEn(LocalDateTime.now());

        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.of(cabys));

        CabysBusquedaDTO resultado = servicio.buscarCabysPorCodigo("2132100000100");

        assertThat(resultado.getExitosa()).isTrue();
        assertThat(resultado.getTotal()).isEqualTo(1);
        assertThat(resultado.getCantidad()).isEqualTo(1);
        assertThat(resultado.getPrimerResultado().getCodigo()).isEqualTo("2132100000100");
        assertThat(resultado.getPrimerResultado().getDescripcion()).isEqualTo("Jugo de tomate concentrado");
        assertThat(resultado.getPrimerResultado().getCategorias())
                .containsExactly("Alimentos", "Bebidas | Refrescos", "Jugos");
        assertThat(resultado.getPrimerResultado().getImpuesto()).isEqualTo(13);
        servidorMock.verify();
        verifyNoInteractions(cabysConsultaLogRepository);
    }

    @Test
    void buscarCabysPorCodigoConCacheMissLlamaAHaciendaYPersisteElLog() {
        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.empty());

        String cuerpo = """
                [
                  {"codigo": "2132100000100", "descripcion": "Jugo de tomate concentrado", "impuesto": 13}
                ]
                """;
        servidorMock.expect(requestTo(startsWith(CABYS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));

        CabysBusquedaDTO resultado = servicio.buscarCabysPorCodigo("2132100000100");

        assertThat(resultado.getExitosa()).isTrue();
        assertThat(resultado.getPrimerResultado().getCodigo()).isEqualTo("2132100000100");

        ArgumentCaptor<CabysConsultaLog> captor = ArgumentCaptor.forClass(CabysConsultaLog.class);
        verify(cabysConsultaLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getCodigo()).isEqualTo("2132100000100");
        assertThat(captor.getValue().isProcesado()).isFalse();
    }

    @Test
    void buscarCabysPorCodigoSinResultadosNoPersisteLog() {
        when(cabysRepository.findById("9999999999999")).thenReturn(Optional.empty());

        servidorMock.expect(requestTo(startsWith(CABYS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CabysBusquedaDTO resultado = servicio.buscarCabysPorCodigo("9999999999999");

        assertThat(resultado.getExitosa()).isFalse();
        verifyNoInteractions(cabysConsultaLogRepository);
    }

    /**
     * Prueba dedicada del bug corregido: antes, {@code timeout} se guardaba en un campo que
     * nunca llegaba a aplicarse a ningún cliente HTTP real (ver el javadoc de
     * {@code HaciendaConsultaServiceImpl#construirRequestFactory}). Se verifica el
     * {@link ClientHttpRequestFactory} directamente en vez de a través de
     * {@link MockRestServiceServer} porque, como explica el javadoc de esta clase, ese mock ya
     * no puede combinarse con el constructor de producción (que fija su propio factory).
     * {@link ReflectionTestUtils} es necesario porque {@link JdkClientHttpRequestFactory} no
     * expone getters públicos para sus campos internos.
     */
    @Test
    void construirRequestFactoryConfiguraTimeoutsDeConexionYLecturaDesdeElValorInyectado() {
        ClientHttpRequestFactory requestFactory = HaciendaConsultaServiceImpl.construirRequestFactory(7);

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);

        Duration readTimeout = (Duration) ReflectionTestUtils.getField(requestFactory, "readTimeout");
        assertThat(readTimeout).isEqualTo(Duration.ofSeconds(7));

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(requestFactory, "httpClient");
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(7));
    }
}
