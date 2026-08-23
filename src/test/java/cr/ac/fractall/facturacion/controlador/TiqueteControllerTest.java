package cr.ac.fractall.facturacion.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.ComprobanteEmisionService;
import cr.ac.fractall.facturacion.servicio.TiqueteService;

/**
 * Prueba unitaria (sin contexto de Spring, {@code standaloneSetup}, mismo patrón que
 * {@code NotaCreditoDebitoControllerTest}) de {@link TiqueteController} -- Release 2 / Fase C.
 * Cubre el shape de request/response, incluyendo el camino sin {@code clienteId} (el hallazgo
 * central de la fase).
 */
class TiqueteControllerTest {

    private static FacturaResponse respuestaMinima(UUID clienteId, String clienteNombre) {
        return new FacturaResponse(
                UUID.randomUUID(), clienteId, clienteNombre,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                UUID.randomUUID(), "SANDBOX", "04", "00100001040000000001",
                "50601010100011010000000001000000001199999999", null,
                "GENERADO", Instant.now(), null, null, BigDecimal.ZERO,
                List.of(), List.of(), List.of(), null, null, null, null, 0);
    }

    private MockMvc mockMvc(TiqueteService servicio) {
        return MockMvcBuilders.standaloneSetup(
                        new TiqueteController(servicio, mock(ComprobanteEmisionService.class)))
                .build();
    }

    @Test
    void crearTiqueteConClienteRetorna201ConClienteIdEnLaRespuesta() throws Exception {
        UUID clienteId = UUID.randomUUID();
        FacturaResponse respuesta = respuestaMinima(clienteId, "Cliente de prueba");
        TiqueteService servicio = mock(TiqueteService.class);
        when(servicio.crear(any())).thenReturn(respuesta);

        String body = """
                {"clienteId":"%s",
                 "lineas":[{"productoId":"%s","cantidad":1,"precioUnitario":1000}]}
                """.formatted(clienteId, UUID.randomUUID());

        mockMvc(servicio).perform(post("/tiquetes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value(clienteId.toString()))
                .andExpect(jsonPath("$.tipoComprobante").value("04"));
    }

    @Test
    void crearTiqueteSinClienteIdRetorna201ConClienteIdNuloEnLaRespuesta() throws Exception {
        FacturaResponse respuesta = respuestaMinima(null, null);
        TiqueteService servicio = mock(TiqueteService.class);
        when(servicio.crear(any())).thenReturn(respuesta);

        String body = """
                {"lineas":[{"productoId":"%s","cantidad":1,"precioUnitario":1000}]}
                """.formatted(UUID.randomUUID());

        mockMvc(servicio).perform(post("/tiquetes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").doesNotExist());
    }

    @Test
    void crearTiqueteSinLineasRetorna400() throws Exception {
        TiqueteService servicio = mock(TiqueteService.class);

        String body = "{\"lineas\":[]}";

        mockMvc(servicio).perform(post("/tiquetes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
