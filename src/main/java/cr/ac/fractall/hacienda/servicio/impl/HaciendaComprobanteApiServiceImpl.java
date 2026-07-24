package cr.ac.fractall.hacienda.servicio.impl;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import cr.ac.fractall.config.CacheConfig;
import cr.ac.fractall.config.CacheConfig;
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.MensajeHaciendaDTO;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.dto.TokenHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import cr.ac.fractall.secretos.SecretosKvService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementación del cliente OAuth2 + recepción de comprobantes de Hacienda -- ver el javadoc de
 * {@link HaciendaComprobanteApiService}.
 *
 * <p>Portado (Categoría A) de {@code HaciendaApiServiceImpl} del ERP de referencia, con tres
 * ajustes exigidos por este proyecto (ninguno reinterpreta la lógica de negocio original, todos
 * son de infraestructura):
 *
 * <ul>
 *   <li>{@link ObjectMapper}/{@link JsonNode} vienen de {@code tools.jackson.databind}, no de
 *       {@code com.fasterxml.jackson.databind} -- Spring Boot 4.1 en este proyecto autoconfigura
 *       Jackson 3 ({@code spring-boot-starter-jackson} trae {@code tools.jackson.core:jackson-databind:3.x}
 *       como dependencia de compilación); el único {@code com.fasterxml.jackson} en el classpath
 *       de este proyecto es Jackson 2, arrastrado transitivamente por {@code jjwt-jackson} (JWT),
 *       no el que Spring inyecta como bean.
 *   <li>Usa {@link RestClient} (inyectado vía {@link RestClient.Builder}) en vez de
 *       {@code RestTemplate} del original, igual que {@code HaciendaConsultaServiceImpl} (Fase 6)
 *       -- ver su javadoc sobre por qué (permite {@code MockRestServiceServer} en pruebas sin
 *       requerir un bean {@code RestTemplate} que este proyecto no expone).
 *   <li>{@code configuracionId}/{@code ConfiguracionHacienda} del original no existen aquí -- se
 *       reemplazan por el {@code UUID} de {@link CredencialHacienda} (ver el javadoc de la
 *       interfaz). Esa entidad NO guarda usuario/password en claro: solo
 *       {@code usuarioHacienda} y una referencia a Vault ({@code credencialReferencia}); el
 *       password real se lee en tiempo de uso vía {@link SecretosKvService#leerSecreto}, nunca se
 *       cachea en memoria más allá del cuerpo del método.
 *   <li>Las 4 URLs OAuth ({@code sandbox}/{@code produccion} x token/API) llevan default explícito
 *       en el propio {@code @Value} (los endpoints reales y públicos de Hacienda, igual que
 *       {@code client-id}), NO solo en {@code application.yaml} -- {@code src/test/resources/application.yaml}
 *       es un archivo de propiedades de prueba completo y SEPARADO (no un complemento del
 *       principal) que ya existía antes de la Fase 8 y no declara {@code application.hacienda.oauth.*};
 *       sin default en el {@code @Value} mismo, CUALQUIER {@code @SpringBootTest} de todo el
 *       proyecto (no solo los de este módulo) fallaría al arrancar con
 *       {@code PlaceholderResolutionException} en cuanto este bean entrara al contexto -- mismo
 *       patrón que ya sigue {@code RESEND_API_KEY} en ese archivo de prueba (ver su comentario
 *       allá).
 * </ul>
 *
 * <h2>Por qué existe el parámetro {@code self}</h2>
 *
 * <p>{@link #autenticar} lleva {@code @Cacheable}, y {@link #renovarToken},
 * {@link #enviarComprobante}, {@link #consultarComprobante} y {@link #consultarMensajes} todos
 * necesitan un token vigente -- en el ERP de referencia, cada uno llama a
 * {@code autenticar(configuracionId)} directamente ({@code this.autenticar(...)} implícito). Eso
 * es exactamente el mismo problema de auto-invocación ya visto en la Fase 7 con
 * {@code @Transactional} (ver la memoria de proyecto): {@code @Cacheable} también se implementa
 * como un proxy Spring AOP alrededor del bean, y una llamada {@code this.autenticar(...)} desde
 * DENTRO de la misma clase nunca pasa por ese proxy -- el caché quedaría completamente inerte en
 * producción (nunca se aplicaría, aunque el atributo siga presente y compile sin errores) sin que
 * ninguna prueba unitaria lo detecte, porque un test que instancia esta clase directamente
 * (sin contexto de Spring) tampoco pasa por ningún proxy. La solución estándar de Spring es
 * inyectar una referencia lazy a la propia interfaz del bean ({@code @Lazy} evita el ciclo de
 * creación durante el arranque) y usarla para las llamadas internas a {@link #autenticar} en vez
 * de {@code this} -- así esas llamadas SÍ atraviesan el proxy y el caché funciona.
 */
@Service
@Slf4j
public class HaciendaComprobanteApiServiceImpl implements HaciendaComprobanteApiService {

    private static final String GRANT_TYPE_PASSWORD = "password";
    private static final String GRANT_TYPE_REFRESH = "refresh_token";
    private static final String AMBIENTE_PRODUCCION = "PRODUCCION";
    private static final String SUBRUTA_PASSWORD_SUFIJO = "/password";

    private final CredencialHaciendaRepository credencialHaciendaRepository;
    private final EmpresaRepository empresaRepository;
    private final SecretosKvService secretosKvService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final HaciendaComprobanteApiService self;
    private final String clientId;
    private final String sandboxTokenUrl;
    private final String sandboxApiUrl;
    private final String produccionTokenUrl;
    private final String produccionApiUrl;

    @Autowired
    public HaciendaComprobanteApiServiceImpl(
            CredencialHaciendaRepository credencialHaciendaRepository,
            EmpresaRepository empresaRepository,
            SecretosKvService secretosKvService,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CacheManager cacheManager,
            @Lazy HaciendaComprobanteApiService self,
            @Value("${application.hacienda.oauth.client-id:api-stag}") String clientId,
            @Value("${application.hacienda.oauth.sandbox.token-url:"
                    + "https://idp.comprobanteselectronicos.go.cr/auth/realms/rut-stag/protocol/openid-connect/token}")
            String sandboxTokenUrl,
            @Value("${application.hacienda.oauth.sandbox.api-url:https://api-sandbox.comprobanteselectronicos.go.cr}")
            String sandboxApiUrl,
            @Value("${application.hacienda.oauth.produccion.token-url:"
                    + "https://idp.comprobanteselectronicos.go.cr/auth/realms/rut/protocol/openid-connect/token}")
            String produccionTokenUrl,
            @Value("${application.hacienda.oauth.produccion.api-url:https://api.comprobanteselectronicos.go.cr}")
            String produccionApiUrl,
            @Value("${application.hacienda.timeout:10}") Integer timeout) {
        this.credencialHaciendaRepository = credencialHaciendaRepository;
        this.empresaRepository = empresaRepository;
        this.secretosKvService = secretosKvService;
        this.restClient = restClientBuilder
                .requestFactory(HaciendaConsultaServiceImpl.construirRequestFactory(timeout))
                .build();
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.self = self;
        this.clientId = clientId;
        this.sandboxTokenUrl = sandboxTokenUrl;
        this.sandboxApiUrl = sandboxApiUrl;
        this.produccionTokenUrl = produccionTokenUrl;
        this.produccionApiUrl = produccionApiUrl;
    }

    /**
     * Constructor visible solo para pruebas: recibe un {@link RestClient} YA construido (p. ej.
     * uno cuyo builder ya fue enlazado a {@code MockRestServiceServer}), igual que
     * {@code HaciendaConsultaServiceImpl} -- ver su javadoc. {@code self} se fija a {@code this}:
     * las pruebas unitarias de esta clase (sin contexto de Spring) no pasan por ningún proxy de
     * todos modos, así que la auto-invocación directa es segura aquí y no reproduce el problema
     * que {@code self} resuelve en producción -- ver el javadoc de la clase.
     */
    HaciendaComprobanteApiServiceImpl(
            CredencialHaciendaRepository credencialHaciendaRepository,
            EmpresaRepository empresaRepository,
            SecretosKvService secretosKvService,
            RestClient restClient,
            ObjectMapper objectMapper,
            CacheManager cacheManager,
            String clientId,
            String sandboxTokenUrl,
            String sandboxApiUrl,
            String produccionTokenUrl,
            String produccionApiUrl) {
        this.credencialHaciendaRepository = credencialHaciendaRepository;
        this.empresaRepository = empresaRepository;
        this.secretosKvService = secretosKvService;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.self = this;
        this.clientId = clientId;
        this.sandboxTokenUrl = sandboxTokenUrl;
        this.sandboxApiUrl = sandboxApiUrl;
        this.produccionTokenUrl = produccionTokenUrl;
        this.produccionApiUrl = produccionApiUrl;
    }

    @Override
    @Cacheable(value = CacheConfig.CACHE_HACIENDA_TOKEN, key = "#credencialId", unless = "#result == null")
    public TokenHaciendaDTO autenticar(UUID credencialId) {
        log.info("Autenticando con Hacienda OAuth2 - Credencial ID: {}", credencialId);

        CredencialHacienda credencial = obtenerCredencial(credencialId);

        try {
            String urlToken = urlTokenPara(credencial.getAmbiente());
            String password = obtenerPassword(credencial);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", GRANT_TYPE_PASSWORD);
            body.add("client_id", clientId);
            body.add("client_secret", "");
            body.add("username", credencial.getUsuarioHacienda());
            body.add("password", password);

            long startTime = System.currentTimeMillis();
            Map<String, Object> respuesta = restClient.post()
                    .uri(urlToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            long responseTime = System.currentTimeMillis() - startTime;

            log.info("Autenticación exitosa - Tiempo: {}ms", responseTime);

            return construirTokenDesdeRespuesta(respuesta);

        } catch (HttpStatusCodeException e) {
            log.error("Error HTTP al autenticar: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Error de autenticación con Hacienda: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error al autenticar con Hacienda: {}", e.getMessage(), e);
            throw new IllegalStateException("Error al autenticar: " + e.getMessage(), e);
        }
    }

    @Override
    public TokenHaciendaDTO renovarToken(String refreshToken, UUID credencialId) {
        log.info("Renovando token OAuth2 - Credencial ID: {}", credencialId);

        CredencialHacienda credencial = obtenerCredencial(credencialId);

        try {
            String urlToken = urlTokenPara(credencial.getAmbiente());

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", GRANT_TYPE_REFRESH);
            body.add("client_id", clientId);
            body.add("client_secret", "");
            body.add("refresh_token", refreshToken);

            Map<String, Object> respuesta = restClient.post()
                    .uri(urlToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            log.info("Token renovado exitosamente");
            return construirTokenDesdeRespuesta(respuesta);

        } catch (Exception e) {
            log.warn("Error renovando token, intentando autenticación completa: {}", e.getMessage());
            return self.autenticar(credencialId);
        }
    }

    @Override
    @CircuitBreaker(name = "haciendaAPI", fallbackMethod = "enviarComprobanteFallback")
    @Retry(name = "haciendaAPI")
    public RespuestaHaciendaDTO enviarComprobante(String xml, String claveNumerica, UUID credencialId) {
        log.info("Enviando comprobante a Hacienda - Clave: {}", claveNumerica);

        CredencialHacienda credencial = obtenerCredencial(credencialId);
        Empresa empresa = obtenerEmpresa(credencial.getEmpresaId());
        TokenHaciendaDTO token = self.autenticar(credencialId);

        String urlRecepcion = urlBasePara(credencial.getAmbiente()) + "/recepcion/v1/recepcion";
        String xmlBase64 = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        String fechaEmision = OffsetDateTime.now(ZoneId.of("America/Costa_Rica"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
        String tipoId = empresa.getTipoIdentificacion() != null ? empresa.getTipoIdentificacion() : "02";
        Map<String, Object> body = Map.of(
                "clave", claveNumerica,
                "fecha", fechaEmision,
                "emisor", Map.of(
                        "tipoIdentificacion", tipoId,
                        "numeroIdentificacion", empresa.getNumeroIdentificacion()),
                "comprobanteXml", xmlBase64);
        log.info("URL de recepción: {}", urlRecepcion);

        try {
            return llamarApiEnvio(urlRecepcion, claveNumerica, body, token.accessToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Token expirado enviando comprobante {} — renovando y reintentando inline", claveNumerica);
            TokenHaciendaDTO tokenNuevo = renovarYActualizarCache(credencialId, token.refreshToken());
            return llamarApiEnvio(urlRecepcion, claveNumerica, body, tokenNuevo.accessToken());
        }
    }

    private RespuestaHaciendaDTO llamarApiEnvio(String urlRecepcion, String claveNumerica,
            Map<String, Object> body, String accessToken) {
        try {
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restClient.post()
                    .uri(urlRecepcion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Respuesta de Hacienda - Status: {} - Tiempo: {}ms", response.getStatusCode(), elapsed);
            return parsearRespuesta(response, claveNumerica, elapsed);

        } catch (HttpClientErrorException.Unauthorized e) {
            throw e;

        } catch (HttpStatusCodeException e) {
            log.error("Error HTTP al enviar comprobante: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(MensajeHacienda.ERROR)
                    .mensaje("Error HTTP: " + e.getStatusCode())
                    .exitoso(false)
                    .detalles(e.getResponseBodyAsString())
                    .codigoHttp(e.getStatusCode().value())
                    .debeReintentar(e.getStatusCode().is5xxServerError())
                    .build();

        } catch (Exception e) {
            log.error("Error al enviar comprobante: {}", e.getMessage(), e);
            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(MensajeHacienda.ERROR)
                    .mensaje("Error de comunicación: " + e.getMessage())
                    .exitoso(false)
                    .debeReintentar(true)
                    .build();
        }
    }

    @Override
    @CircuitBreaker(name = "haciendaAPI", fallbackMethod = "consultarComprobanteFallback")
    @Retry(name = "haciendaAPI")
    public RespuestaHaciendaDTO consultarComprobante(String claveNumerica, UUID credencialId) {
        log.info("Consultando comprobante en Hacienda - Clave: {}", claveNumerica);

        CredencialHacienda credencial = obtenerCredencial(credencialId);
        TokenHaciendaDTO token = self.autenticar(credencialId);
        String urlConsulta = urlBasePara(credencial.getAmbiente()) + "/recepcion/v1/recepcion/" + claveNumerica;

        try {
            return llamarApiConsulta(urlConsulta, claveNumerica, token.accessToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Token expirado consultando comprobante {} — renovando y reintentando inline", claveNumerica);
            TokenHaciendaDTO tokenNuevo = renovarYActualizarCache(credencialId, token.refreshToken());
            return llamarApiConsulta(urlConsulta, claveNumerica, tokenNuevo.accessToken());
        }
    }

    private RespuestaHaciendaDTO llamarApiConsulta(String urlConsulta, String claveNumerica, String accessToken) {
        try {
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restClient.get()
                    .uri(urlConsulta)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .toEntity(String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Consulta exitosa - Status: {} - Tiempo: {}ms", response.getStatusCode(), elapsed);
            return parsearRespuesta(response, claveNumerica, elapsed);

        } catch (HttpClientErrorException.Unauthorized e) {
            throw e;

        } catch (HttpClientErrorException.NotFound e) {
            log.info("Comprobante no encontrado en Hacienda: {}", claveNumerica);
            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(MensajeHacienda.PROCESANDO)
                    .mensaje("Comprobante no encontrado - aún en procesamiento")
                    .exitoso(false)
                    .codigoHttp(404)
                    .debeReintentar(true)
                    .build();

        } catch (HttpStatusCodeException e) {
            log.error("Error HTTP al consultar: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Error consultando comprobante", e);

        } catch (Exception e) {
            log.error("Error al consultar comprobante: {}", e.getMessage(), e);
            throw new IllegalStateException("Error en consulta: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MensajeHaciendaDTO> consultarMensajes(UUID credencialId) {
        log.info("Consultando mensajes de Hacienda - Credencial ID: {}", credencialId);

        CredencialHacienda credencial = obtenerCredencial(credencialId);
        Empresa empresa = obtenerEmpresa(credencial.getEmpresaId());

        try {
            TokenHaciendaDTO token = self.autenticar(credencialId);

            String urlMensajes = urlBasePara(credencial.getAmbiente())
                    + "/mensajes-receptor/v1/mensajes/" + empresa.getNumeroIdentificacion();

            String cuerpo = restClient.get()
                    .uri(urlMensajes)
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve()
                    .body(String.class);

            if (cuerpo == null || cuerpo.isBlank()) {
                return new ArrayList<>();
            }

            JsonNode root = objectMapper.readTree(cuerpo);
            List<MensajeHaciendaDTO> mensajes = new ArrayList<>();

            if (root.has("mensajes") && root.get("mensajes").isArray()) {
                for (JsonNode mensaje : root.get("mensajes")) {
                    mensajes.add(new MensajeHaciendaDTO(
                            mensaje.get("clave").asString(),
                            mensaje.get("mensaje").asString(),
                            mensaje.has("detalle") ? mensaje.get("detalle").asString() : "",
                            mensaje.get("estado").asString()));
                }
            }

            log.info("Encontrados {} mensajes de Hacienda", mensajes.size());
            return mensajes;

        } catch (Exception e) {
            log.error("Error consultando mensajes: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // ========== MÉTODOS AUXILIARES ==========

    private CredencialHacienda obtenerCredencial(UUID credencialId) {
        return credencialHaciendaRepository.findById(credencialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Credencial de Hacienda no encontrada: " + credencialId));
    }

    private Empresa obtenerEmpresa(UUID empresaId) {
        return empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "Credencial de Hacienda apunta a un empresa_id inexistente: " + empresaId));
    }

    /**
     * Lee la contraseña OAuth2 real desde Vault -- {@link CredencialHacienda} solo guarda
     * {@code usuarioHacienda} y la referencia a la subruta, nunca la contraseña en claro (ver el
     * javadoc de la clase). La subruta sigue la misma convención que
     * {@code EmpresaService#configurarCredencialHacienda} (constante
     * {@code SUBRUTA_HACIENDA_SANDBOX_PASSWORD} allá): {@code hacienda/<ambiente-en-minuscula>/password}.
     */
    private String obtenerPassword(CredencialHacienda credencial) {
        String subruta = "hacienda/" + credencial.getAmbiente().toLowerCase(Locale.ROOT) + SUBRUTA_PASSWORD_SUFIJO;
        return secretosKvService.leerSecreto(credencial.getEmpresaId(), subruta)
                .orElseThrow(() -> new IllegalStateException(
                        "No hay contraseña de Hacienda en Vault para la credencial " + credencial.getId()));
    }

    private String urlTokenPara(String ambiente) {
        return AMBIENTE_PRODUCCION.equals(ambiente) ? produccionTokenUrl : sandboxTokenUrl;
    }

    private String urlBasePara(String ambiente) {
        return AMBIENTE_PRODUCCION.equals(ambiente) ? produccionApiUrl : sandboxApiUrl;
    }

    private TokenHaciendaDTO construirTokenDesdeRespuesta(Map<String, Object> respuesta) {
        if (respuesta == null) {
            throw new IllegalStateException("Respuesta vacía de Hacienda al autenticar");
        }
        return new TokenHaciendaDTO(
                (String) respuesta.get("access_token"),
                (String) respuesta.get("refresh_token"),
                (Integer) respuesta.get("expires_in"),
                (String) respuesta.get("token_type"));
    }

    /**
     * Parsea la respuesta de Hacienda y crea DTO.
     */
    private RespuestaHaciendaDTO parsearRespuesta(ResponseEntity<String> response,
            String claveNumerica,
            long responseTime) {
        // 202 = Hacienda aceptó el comprobante para procesamiento asíncrono (body vacío esperado)
        if (response.getBody() == null || response.getBody().isBlank()) {
            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(MensajeHacienda.PROCESANDO)
                    .mensaje("Comprobante recibido por Hacienda, en procesamiento")
                    .exitoso(false)
                    .codigoHttp(response.getStatusCode().value())
                    .tiempoRespuestaMs(responseTime)
                    .debeReintentar(false)
                    .build();
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());

            // Hacienda retorna "ind-estado" (con guion), no camelCase
            String codigoMensaje = root.has("ind-estado") ? root.get("ind-estado").asString()
                    : (root.has("indEstado") ? root.get("indEstado").asString() : "");
            log.info("Hacienda ind-estado: '{}'", codigoMensaje);
            MensajeHacienda mensaje = determinarMensaje(codigoMensaje);

            String xmlRespuesta = root.has("respuesta-xml") ? root.get("respuesta-xml").asString() : null;
            String mensajeTexto = root.has("mensaje") ? root.get("mensaje").asString() : "";

            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(mensaje)
                    .mensaje(mensajeTexto)
                    .xmlRespuesta(xmlRespuesta)
                    .indicadorEstado(codigoMensaje)
                    .exitoso(mensaje == MensajeHacienda.ACEPTADO)
                    .detalles(response.getBody())
                    .codigoHttp(response.getStatusCode().value())
                    .tiempoRespuestaMs(responseTime)
                    .debeReintentar(mensaje == MensajeHacienda.PROCESANDO)
                    .build();

        } catch (Exception e) {
            log.error("Error parseando respuesta de Hacienda: {}", e.getMessage());

            return RespuestaHaciendaDTO.builder()
                    .claveNumerica(claveNumerica)
                    .fechaRespuesta(LocalDateTime.now())
                    .codigoMensaje(MensajeHacienda.ERROR)
                    .mensaje("Error parseando respuesta")
                    .exitoso(false)
                    .detalles(response.getBody())
                    .codigoHttp(response.getStatusCode().value())
                    .tiempoRespuestaMs(responseTime)
                    .build();
        }
    }

    /**
     * Determina el MensajeHacienda según código de respuesta.
     */
    private MensajeHacienda determinarMensaje(String codigo) {
        if (codigo == null || codigo.isEmpty()) {
            return MensajeHacienda.PROCESANDO;
        }

        return switch (codigo.toLowerCase(Locale.ROOT)) {
            case "aceptado", "1" -> MensajeHacienda.ACEPTADO;
            case "rechazado", "2" -> MensajeHacienda.RECHAZADO;
            case "procesando", "0" -> MensajeHacienda.PROCESANDO;
            default -> MensajeHacienda.ERROR;
        };
    }

    private TokenHaciendaDTO renovarYActualizarCache(UUID credencialId, String refreshToken) {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_HACIENDA_TOKEN);
        if (cache != null) {
            cache.evict(credencialId);
        }
        TokenHaciendaDTO tokenNuevo = renovarToken(refreshToken, credencialId);
        if (cache != null) {
            cache.put(credencialId, tokenNuevo);
        }
        log.info("Token de Hacienda renovado para credencial {}", credencialId);
        return tokenNuevo;
    }

    // ========================================
    // MÉTODOS FALLBACK PARA CIRCUIT BREAKER
    // ========================================

    /**
     * Método fallback para enviarComprobante cuando el Circuit Breaker se abre.
     */
    private RespuestaHaciendaDTO enviarComprobanteFallback(
            String xml,
            String claveNumerica,
            UUID credencialId,
            Throwable e) {

        log.error("Circuit Breaker ABIERTO - Fallback activado para enviarComprobante: {}", e.getMessage());

        return RespuestaHaciendaDTO.builder()
                .claveNumerica(claveNumerica)
                .fechaRespuesta(LocalDateTime.now())
                .codigoMensaje(MensajeHacienda.ERROR)
                .mensaje("Servicio de Hacienda temporalmente no disponible. Se reintentará automáticamente.")
                .indicadorEstado("circuit-breaker-open")
                .exitoso(false)
                .detalles("Circuit Breaker activado debido a múltiples fallos. Causa: "
                        + e.getClass().getSimpleName() + " - " + e.getMessage())
                .codigoHttp(503)
                .debeReintentar(true)
                .build();
    }

    /**
     * Método fallback para consultarComprobante cuando el Circuit Breaker se abre.
     */
    private RespuestaHaciendaDTO consultarComprobanteFallback(
            String claveNumerica,
            UUID credencialId,
            Throwable e) {

        log.error("Circuit Breaker ABIERTO - Fallback activado para consultarComprobante: {}", e.getMessage());

        return RespuestaHaciendaDTO.builder()
                .claveNumerica(claveNumerica)
                .fechaRespuesta(LocalDateTime.now())
                .codigoMensaje(MensajeHacienda.PROCESANDO)
                .mensaje("No se pudo consultar estado. El comprobante se encuentra en procesamiento.")
                .indicadorEstado("circuit-breaker-open")
                .exitoso(false)
                .detalles("Circuit Breaker activado. Consulta automática se realizará posteriormente.")
                .codigoHttp(503)
                .debeReintentar(true)
                .build();
    }
}
