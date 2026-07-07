package cr.ac.fractall.hacienda.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;

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
 */
class HaciendaConsultaServiceImplTest {

    private static final String CABYS_URL = "https://api.hacienda.go.cr/fe/cabys";

    private MockRestServiceServer servidorMock;
    private HaciendaConsultaServiceImpl servicio;

    @BeforeEach
    void configurar() {
        RestClient.Builder builder = RestClient.builder();
        servidorMock = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        servicio = new HaciendaConsultaServiceImpl(restClient, "https://api.hacienda.go.cr/fe/ae", CABYS_URL);
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
