package cr.ac.fractall.shared;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import cr.ac.fractall.catalogo.controlador.ClienteController;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ClienteService;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.ComprobanteNoReenviableException;
import cr.ac.fractall.facturacion.servicio.CondicionVentaInvalidaException;
import cr.ac.fractall.facturacion.servicio.ContadorConsecutivoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.CredencialHaciendaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.EmpresaSinCorreoElectronicoException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoAplicableAFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoPerteneceAlClienteException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoVigenteException;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.hacienda.servicio.TipoCambioNoDisponibleException;

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

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new ClienteController(clienteServiceMock, mock(HaciendaApiService.class)))
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

    @Test
    void handler_comprobanteNoReenviable_devuelve409ConMensajeResponse() throws Exception {
        UUID facturaId = UUID.randomUUID();

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ControladorDeReenvioStub(facturaId))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/test/reenviar/" + facturaId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_tipoCambioNoDisponible_devuelve503ConMensajeResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ControladorDeTipoCambioStub())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/test/tipo-cambio"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").value(
                        "No fue posible obtener el tipo de cambio del dólar porque la API de Hacienda no está disponible: Timeout"));
    }

    /** Controlador mínimo para disparar TipoCambioNoDisponibleException desde el advice. */
    @RestController
    static class ControladorDeTipoCambioStub {

        @PostMapping("/test/tipo-cambio")
        public void consultar() {
            throw new TipoCambioNoDisponibleException("Timeout");
        }
    }

    /** Controlador mínimo para disparar ComprobanteNoReenviableException desde el advice. */
    @RestController
    static class ControladorDeReenvioStub {

        private final UUID facturaId;

        ControladorDeReenvioStub(UUID facturaId) {
            this.facturaId = facturaId;
        }

        @PostMapping("/test/reenviar/{id}")
        public void reenviar(@PathVariable UUID id) {
            throw new ComprobanteNoReenviableException(facturaId, "ACEPTADO");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fase B / PR2: migración de las 10 excepciones que FacturaController capturaba
    // explícitamente (D-G) -- ahora mapeadas globalmente por el advice, con los mismos 3
    // statuses agrupados y mismo MensajeResponse. "Ensanchamiento de comportamiento registrado":
    // estas 10 excepciones ahora se capturan en CUALQUIER endpoint, no solo POST /facturas.
    // ─────────────────────────────────────────────────────────────────────────

    /** Controlador mínimo para disparar las 10 excepciones migradas desde cualquier endpoint. */
    @RestController
    static class ControladorDeExcepcionesMigradasStub {

        @PostMapping("/test/cliente-no-encontrado")
        public void clienteNoEncontrado() {
            throw new ClienteNoEncontradoException(UUID.randomUUID());
        }

        @PostMapping("/test/producto-no-encontrado")
        public void productoNoEncontrado() {
            throw new ProductoNoEncontradoException(UUID.randomUUID());
        }

        @PostMapping("/test/cliente-exoneracion-no-encontrada")
        public void clienteExoneracionNoEncontrada() {
            throw new ClienteExoneracionNoEncontradaException(UUID.randomUUID());
        }

        @PostMapping("/test/exoneracion-no-pertenece-al-cliente")
        public void exoneracionNoPerteneceAlCliente() {
            throw new ExoneracionNoPerteneceAlClienteException(UUID.randomUUID(), UUID.randomUUID());
        }

        @PostMapping("/test/exoneracion-no-aplicable")
        public void exoneracionNoAplicable() {
            throw new ExoneracionNoAplicableAFacturaElectronicaException(UUID.randomUUID(), "01");
        }

        @PostMapping("/test/exoneracion-no-vigente")
        public void exoneracionNoVigente() {
            throw new ExoneracionNoVigenteException(UUID.randomUUID());
        }

        @PostMapping("/test/condicion-venta-invalida")
        public void condicionVentaInvalida() {
            throw new CondicionVentaInvalidaException("plazoCredito es obligatorio");
        }

        @PostMapping("/test/contador-consecutivo-no-encontrado")
        public void contadorConsecutivoNoEncontrado() {
            throw new ContadorConsecutivoNoEncontradoException(UUID.randomUUID(), "SANDBOX", "01");
        }

        @PostMapping("/test/credencial-hacienda-no-encontrada")
        public void credencialHaciendaNoEncontrada() {
            throw new CredencialHaciendaNoEncontradaException(UUID.randomUUID(), "SANDBOX");
        }

        @PostMapping("/test/empresa-sin-correo")
        public void empresaSinCorreo() {
            throw new EmpresaSinCorreoElectronicoException(UUID.randomUUID());
        }
    }

    private MockMvc mockMvcConExcepcionesMigradas() {
        return MockMvcBuilders.standaloneSetup(new ControladorDeExcepcionesMigradasStub())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handler_clienteNoEncontrado_devuelve404() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/cliente-no-encontrado"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_productoNoEncontrado_devuelve404() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/producto-no-encontrado"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_clienteExoneracionNoEncontrada_devuelve404() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/cliente-exoneracion-no-encontrada"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_exoneracionNoPerteneceAlCliente_devuelve400() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/exoneracion-no-pertenece-al-cliente"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_exoneracionNoAplicable_devuelve400() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/exoneracion-no-aplicable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_exoneracionNoVigente_devuelve400() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/exoneracion-no-vigente"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_condicionVentaInvalida_devuelve400() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/condicion-venta-invalida"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_contadorConsecutivoNoEncontrado_devuelve503() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/contador-consecutivo-no-encontrado"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_credencialHaciendaNoEncontrada_devuelve503() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/credencial-hacienda-no-encontrada"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isString());
    }

    @Test
    void handler_empresaSinCorreo_devuelve503() throws Exception {
        mockMvcConExcepcionesMigradas().perform(post("/test/empresa-sin-correo"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isString());
    }
}
