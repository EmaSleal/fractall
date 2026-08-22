package cr.ac.fractall.facturacion.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.ComprobanteEmisionService;
import cr.ac.fractall.facturacion.servicio.NotaCreditoDebitoService;

/**
 * Prueba unitaria (sin contexto de Spring completo, {@code standaloneSetup}, mismo patrón que
 * {@code GlobalExceptionHandlerTest}) de {@link NotaCreditoController}/{@link NotaDebitoController}
 * -- Release 2 / Fase B, PR3.
 *
 * <p><b>Nota sobre la regla 4/6 estructural (desviación verificada frente al spec):</b> el
 * spec de esta fase asumía que enviar {@code clienteId}/{@code numero}/{@code tipoDocIr}/{@code
 * fechaEmisionIr} en el body produciría 400 por "propiedad JSON desconocida". Se verificó contra
 * el {@code DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES} real de {@code tools.jackson}
 * (Jackson 3.1.4, el que trae Spring Boot 4.1/{@code spring-boot-jackson}): su default cambió a
 * {@code false} en Jackson 3 (a diferencia de Jackson 2, donde era {@code true}), y este proyecto
 * no lo sobreescribe en {@code application.yaml}. La garantía real que SÍ se cumple -- y es la que
 * realmente importa para las reglas 4/6 -- es estructural: {@link
 * cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest}/{@link
 * cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest} no tienen esos campos, así que Jackson
 * los descarta silenciosamente en vez de rechazarlos -- nunca llegan a bindearse a ningún lado, ni
 * siquiera con un valor null accesible. Esta prueba documenta ese comportamiento REAL (200/201,
 * propiedades desconocidas ignoradas sin error) en vez de afirmar un 400 que no ocurre. La
 * garantía de que el valor derivado en servidor (no el inyectado) es el que se persiste está
 * cubierta a nivel de servicio por {@code NotaCreditoDebitoServiceTest}.
 */
class NotaCreditoDebitoControllerTest {

    private static FacturaResponse respuestaMinima() {
        return new FacturaResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Cliente de prueba",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                UUID.randomUUID(), "SANDBOX", "03", "00100001010000000001",
                "50601010100011010000000001000000001100000000001", UUID.randomUUID(),
                "GENERADO", LocalDateTime.now(), null, null, BigDecimal.ZERO,
                List.of(), List.of(), List.of(), null, null, null, null, 0);
    }

    private MockMvc mockMvcNotaCredito(NotaCreditoDebitoService servicio) {
        return MockMvcBuilders.standaloneSetup(
                        new NotaCreditoController(servicio, mock(ComprobanteEmisionService.class)))
                .build();
    }

    private MockMvc mockMvcNotaDebito(NotaCreditoDebitoService servicio) {
        return MockMvcBuilders.standaloneSetup(
                        new NotaDebitoController(servicio, mock(ComprobanteEmisionService.class)))
                .build();
    }

    @Test
    void crearNotaCreditoConClienteIdEnElBodyIgnoraLaPropiedadDesconocidaYProcesaNormalmente() throws Exception {
        FacturaResponse respuesta = respuestaMinima();
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);
        when(servicio.crearNotaCredito(any())).thenReturn(respuesta);

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"02","razon":"Corrección",
                 "lineas":[{"lineaFacturaOrigenId":"%s","cantidad":1}],
                 "clienteId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        // El clienteId inyectado en el body NUNCA llega al servicio (el DTO no tiene ese campo) --
        // la respuesta refleja el clienteId que el servicio (mockeado aquí) ya decidió, no el
        // inyectado por el cliente HTTP.
        mockMvcNotaCredito(servicio).perform(post("/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value(respuesta.clienteId().toString()));
    }

    @Test
    void crearNotaCreditoConNumeroEnElBodyIgnoraLaPropiedadDesconocidaYProcesaNormalmente() throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);
        when(servicio.crearNotaCredito(any())).thenReturn(respuestaMinima());

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"02","razon":"Corrección",
                 "lineas":[{"lineaFacturaOrigenId":"%s","cantidad":1}],
                 "numero":"00100001010000000001"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaCredito(servicio).perform(post("/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void crearNotaCreditoConTipoDocIrEnElBodyIgnoraLaPropiedadDesconocidaYProcesaNormalmente() throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);
        when(servicio.crearNotaCredito(any())).thenReturn(respuestaMinima());

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"02","razon":"Corrección",
                 "lineas":[{"lineaFacturaOrigenId":"%s","cantidad":1}],
                 "tipoDocIr":"01"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaCredito(servicio).perform(post("/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void crearNotaCreditoConFechaEmisionIrEnElBodyIgnoraLaPropiedadDesconocidaYProcesaNormalmente() throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);
        when(servicio.crearNotaCredito(any())).thenReturn(respuestaMinima());

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"02","razon":"Corrección",
                 "lineas":[{"lineaFacturaOrigenId":"%s","cantidad":1}],
                 "fechaEmisionIr":"2024-01-01"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaCredito(servicio).perform(post("/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void crearNotaDebitoConLosCuatroCamposProhibidosEnElBodyIgnoraLasPropiedadesDesconocidasYProcesaNormalmente()
            throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);
        when(servicio.crearNotaDebito(any())).thenReturn(respuestaMinima());

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"01","razon":"Cargo olvidado",
                 "lineas":[{"productoId":"%s","cantidad":1,"precioUnitario":500}],
                 "clienteId":"%s","numero":"00100001010000000001","tipoDocIr":"01","fechaEmisionIr":"2024-01-01"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaDebito(servicio).perform(post("/notas-debito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Cierra el gap CRITICAL detectado por sdd-verify: {@code CrearNotaCreditoRequest} declara
     * {@code @NotBlank} en {@code codigoReferencia}, pero hasta ahora ningún test del change
     * mandaba ese campo en blanco y confirmaba el 400 -- solo se probaba la parte estructural
     * (campos prohibidos ausentes), nunca el rechazo de Bean Validation en sí. No requiere cambio
     * de producción: la validación ya existe en el DTO.
     */
    @Test
    void crearNotaCreditoConCodigoReferenciaEnBlancoRetorna400() throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"","razon":"Corrección",
                 "lineas":[{"lineaFacturaOrigenId":"%s","cantidad":1}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaCredito(servicio).perform(post("/notas-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Contraparte de {@link #crearNotaCreditoConCodigoReferenciaEnBlancoRetorna400} para ND --
     * mismo gap CRITICAL, mismo DTO field, mismo fix (solo cobertura, sin cambio de producción).
     */
    @Test
    void crearNotaDebitoConCodigoReferenciaEnBlancoRetorna400() throws Exception {
        NotaCreditoDebitoService servicio = mock(NotaCreditoDebitoService.class);

        String body = """
                {"facturaReferenciaId":"%s","codigoReferencia":"","razon":"Cargo olvidado",
                 "lineas":[{"productoId":"%s","cantidad":1,"precioUnitario":500}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvcNotaDebito(servicio).perform(post("/notas-debito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
