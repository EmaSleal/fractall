package cr.ac.fractall.hacienda.servicio.impl;

import java.time.Duration;
import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.dto.HaciendaConsultaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementación del servicio para consultar la API pública de Hacienda Costa Rica.
 * Utiliza {@link RestClient} de Spring Framework para las llamadas HTTP.
 *
 * <p>Portado de {@code HaciendaConsultaServiceImpl} (Categoría A) para la Fase 6 -- ver el
 * javadoc de {@link HaciendaApiService}.
 *
 * <p>Deliberadamente inyecta {@link RestClient.Builder} en vez de construir un
 * {@code RestClient} en un constructor sin argumentos como el original: Spring Boot ya expone un
 * bean {@code RestClient.Builder} autoconfigurado, y hacerlo así permite
 * {@code MockRestServiceServer.bindTo(RestClient.Builder)} en pruebas -- sin este cambio no
 * habría forma de interceptar la llamada HTTP real a {@code api.hacienda.go.cr} en un test
 * unitario. Los tres parámetros de configuración también se reciben por constructor (con
 * {@code @Value}) en lugar de inyección por campo, por la misma razón: permite instanciar esta
 * clase directamente en un test sin arrancar contexto de Spring.
 *
 * <p>Este constructor SÍ fija su propio {@link ClientHttpRequestFactory} (con los timeouts de
 * {@code application.hacienda.timeout}) en el {@code RestClient.Builder} recibido, lo que
 * reemplaza cualquier factory que ya se le hubiera inyectado -- por eso
 * {@code MockRestServiceServer.bindTo(RestClient.Builder)} ya NO puede combinarse con este
 * constructor (el mock necesita ser el último en fijar el factory del builder antes de
 * {@code build()}). Para pruebas que necesiten interceptar la llamada HTTP, usar el constructor
 * de paquete que recibe un {@link RestClient} ya construido (ver
 * {@code HaciendaConsultaServiceImplTest}).
 */
@Service
@Slf4j
public class HaciendaConsultaServiceImpl implements HaciendaApiService {

    private final RestClient restClient;
    private final String haciendaApiUrl;
    private final String haciendaCabysUrl;

    @Autowired
    public HaciendaConsultaServiceImpl(
            RestClient.Builder restClientBuilder,
            @Value("${application.hacienda.api.url:https://api.hacienda.go.cr/fe/ae}") String haciendaApiUrl,
            @Value("${application.hacienda.cabys.url:https://api.hacienda.go.cr/fe/cabys}") String haciendaCabysUrl,
            @Value("${application.hacienda.timeout:10}") Integer timeout) {
        this.restClient = restClientBuilder
                .requestFactory(construirRequestFactory(timeout))
                .defaultHeader("User-Agent", "curl/7.81.0")
                .defaultHeader("Accept", "*/*")
                .build();
        this.haciendaApiUrl = haciendaApiUrl;
        this.haciendaCabysUrl = haciendaCabysUrl;
    }

    /**
     * Constructor visible solo para pruebas: recibe un {@link RestClient} YA construido (p. ej.
     * uno cuyo builder ya fue enlazado a {@code MockRestServiceServer}) en vez de un
     * {@code RestClient.Builder}, precisamente para no pisar el
     * {@link org.springframework.http.client.ClientHttpRequestFactory} que el mock ya haya
     * fijado -- ver el javadoc de la clase. No recibe {@code timeout}: ningún método de la clase
     * lo lee después de construir el {@code RestClient} (solo se usa una vez, como parámetro
     * local de {@link #construirRequestFactory}), así que no hay nada que un constructor de
     * prueba necesite guardar.
     */
    HaciendaConsultaServiceImpl(RestClient restClient, String haciendaApiUrl, String haciendaCabysUrl) {
        this.restClient = restClient;
        this.haciendaApiUrl = haciendaApiUrl;
        this.haciendaCabysUrl = haciendaCabysUrl;
    }

    /**
     * El {@code timeout} (segundos, {@code application.hacienda.timeout}) antes se guardaba en
     * un campo que nunca se aplicaba a ningún cliente HTTP real -- sin un
     * {@link ClientHttpRequestFactory} configurado explícitamente, {@link RestClient} no impone
     * ni timeout de conexión ni de lectura, así que una llamada colgada a
     * {@code api.hacienda.go.cr} podía bloquear indefinidamente un método {@code @Transactional}
     * ({@code ProductoService#crear}/{@code #actualizar}), arriesgando agotar el pool de
     * conexiones de la base de datos. {@link JdkClientHttpRequestFactory} (basado en
     * {@link HttpClient} del JDK, disponible desde Java 11) se eligió sobre
     * {@code SimpleClientHttpRequestFactory} por ser la opción moderna recomendada por Spring
     * Framework y no requerir ninguna dependencia adicional en un proyecto Java 21.
     *
     * <p>Visibilidad de paquete (no {@code private}) a propósito: permite que
     * {@code HaciendaConsultaServiceImplTest} verifique directamente que los timeouts de
     * conexión y lectura quedan configurados con el valor esperado.
     */
    static ClientHttpRequestFactory construirRequestFactory(Integer timeoutSegundos) {
        Duration duracion = Duration.ofSeconds(timeoutSegundos);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(duracion)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(duracion);
        return requestFactory;
    }

    @Override
    public HaciendaConsultaDTO consultarContribuyente(String numeroIdentificacion) {
        // Validar entrada
        if (numeroIdentificacion == null || numeroIdentificacion.trim().isEmpty()) {
            log.warn("Número de identificación vacío");
            return HaciendaConsultaDTO.builder()
                .exitosa(false)
                .mensajeError("Número de identificación requerido")
                .build();
        }

        // Limpiar número (remover guiones, espacios)
        String numeroLimpio = numeroIdentificacion.replaceAll("[\\s-]", "");

        log.info("Consultando API Hacienda para identificación: {}", numeroLimpio);

        try {
            // Construir URL con query parameter
            String url = haciendaApiUrl + "?identificacion=" + numeroLimpio;

            log.debug("URL construida para consulta: {}", url);

            // Realizar petición GET
            ResponseEntity<HaciendaConsultaDTO> response = restClient.get()
                .uri(url)
                .retrieve()
                .toEntity(HaciendaConsultaDTO.class);

            // Verificar respuesta exitosa
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                HaciendaConsultaDTO resultado = response.getBody();
                resultado.setExitosa(true);

                log.info("Consulta exitosa para {}: {} (Tipo: {})",
                    numeroLimpio,
                    resultado.getNombre(),
                    resultado.getTipoIdentificacion());

                return resultado;
            } else {
                log.warn("Respuesta no exitosa de Hacienda: {}", response.getStatusCode());
                return HaciendaConsultaDTO.builder()
                    .exitosa(false)
                    .mensajeError("Identificación no encontrada en Hacienda")
                    .build();
            }

        } catch (RestClientException e) {
            log.error("Error al consultar API de Hacienda para {}: {}", numeroLimpio, e.getMessage());

            // Verificar si es error 404 (no encontrado)
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return HaciendaConsultaDTO.builder()
                    .exitosa(false)
                    .mensajeError("Identificación no encontrada en el registro de Hacienda")
                    .build();
            }

            return HaciendaConsultaDTO.builder()
                .exitosa(false)
                .mensajeError("Error al conectar con Hacienda: " + e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("Error inesperado al consultar Hacienda: {}", e.getMessage(), e);
            return HaciendaConsultaDTO.builder()
                .exitosa(false)
                .mensajeError("Error inesperado: " + e.getMessage())
                .build();
        }
    }

    @Override
    public boolean validarIdentificacion(String numeroIdentificacion) {
        HaciendaConsultaDTO resultado = consultarContribuyente(numeroIdentificacion);
        // Validar que existe, está inscrito y al día
        return resultado != null &&
               resultado.getExitosa() != null &&
               resultado.getExitosa() &&
               resultado.isEstaInscrito();
    }

    @Override
    public CabysBusquedaDTO buscarCabys(String busqueda, Integer top) {
        // Validar entrada
        if (busqueda == null || busqueda.trim().isEmpty()) {
            log.warn("Búsqueda CABYS vacía");
            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("Término de búsqueda requerido")
                .build();
        }

        // Establecer top por defecto
        if (top == null || top <= 0) {
            top = 10;
        }

        log.info("Buscando códigos CABYS para: '{}' (top: {})", busqueda, top);

        try {
            // Construir URL con query parameters
            String url = UriComponentsBuilder.fromUriString(haciendaCabysUrl)
                .queryParam("q", busqueda)
                .queryParam("top", top)
                .encode()
                .toUriString();

            // Realizar petición GET
            ResponseEntity<CabysBusquedaDTO> response = restClient.get()
                .uri(url)
                .retrieve()
                .toEntity(CabysBusquedaDTO.class);

            // Verificar respuesta exitosa
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CabysBusquedaDTO resultado = response.getBody();
                resultado.setExitosa(true);

                log.info("Búsqueda CABYS exitosa: {} resultados (total: {})",
                    resultado.getCantidad(),
                    resultado.getTotal());

                return resultado;
            } else {
                log.warn("Respuesta no exitosa de API CABYS: {}", response.getStatusCode());
                return CabysBusquedaDTO.builder()
                    .exitosa(false)
                    .mensajeError("No se encontraron códigos CABYS")
                    .build();
            }

        } catch (RestClientException e) {
            log.error("Error al buscar CABYS para '{}': {}", busqueda, e.getMessage());

            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("Error al conectar con API CABYS: " + e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("Error inesperado al buscar CABYS: {}", e.getMessage(), e);
            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("Error inesperado: " + e.getMessage())
                .build();
        }
    }

    @Override
    public CabysBusquedaDTO buscarCabysPorCodigo(String codigo) {
        if (codigo == null || codigo.trim().isEmpty()) {
            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("Código CABYS requerido")
                .build();
        }

        log.info("Buscando código CABYS exacto: '{}'", codigo);

        try {
            String url = UriComponentsBuilder.fromUriString(haciendaCabysUrl)
                .queryParam("codigo", codigo.trim())
                .encode()
                .toUriString();

            ResponseEntity<CabysBusquedaDTO> response = restClient.get()
                .uri(url)
                .retrieve()
                .toEntity(CabysBusquedaDTO.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                CabysBusquedaDTO resultado = response.getBody();
                resultado.setExitosa(true);
                return resultado;
            }

            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("No se encontró el código CABYS")
                .build();

        } catch (RestClientException e) {
            log.error("Error al buscar código CABYS '{}': {}", codigo, e.getMessage());
            return CabysBusquedaDTO.builder()
                .exitosa(false)
                .mensajeError("Error al conectar con API CABYS: " + e.getMessage())
                .build();
        }
    }
}
