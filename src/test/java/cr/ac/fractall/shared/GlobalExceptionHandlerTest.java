package cr.ac.fractall.shared;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import cr.ac.fractall.catalogo.controlador.ClienteController;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.catalogo.servicio.ClienteService;

/**
 * Prueba unitaria (sin contexto de Spring completo, {@code standaloneSetup}) de
 * {@link GlobalExceptionHandler} -- el backstop de defensa en profundidad para el patrón
 * "check-then-act" de {@code ClienteService}/{@code ProductoService}/
 * {@code ClienteExoneracionService} (ver su javadoc). Se simula la carrera SIN concurrencia real:
 * el servicio se mockea para lanzar directamente la {@code DataIntegrityViolationException} que
 * ocurriría si dos solicitudes concurrentes pasaran ambas el pre-chequeo antes de que cualquiera
 * hiciera commit -- lo que importa probar aquí es que el advice, y no el controlador, es quien
 * traduce esa excepción a un 409 limpio en vez de un 500 crudo.
 */
class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unaViolacionDeIntegridadNoCapturadaPorElControladorSeConvierteEn409ViaElAdvice() throws Exception {
        ClienteService clienteServiceMock = mock(ClienteService.class);
        when(clienteServiceMock.crear(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ClienteController(clienteServiceMock))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        CrearClienteRequest request = new CrearClienteRequest(
                "Cliente de prueba", "01", "107890123", null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/catalogo/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El recurso ya existe o viola una restricción de unicidad."));
    }
}
