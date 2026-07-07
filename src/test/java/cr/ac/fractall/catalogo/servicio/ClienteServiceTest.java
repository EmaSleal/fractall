package cr.ac.fractall.catalogo.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.dto.ActualizarClienteRequest;
import cr.ac.fractall.catalogo.dto.ClienteResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;

/**
 * Prueba unitaria de {@link ClienteService} con {@code ClienteRepository} mockeado (sin
 * contexto de Spring, sin base de datos real) -- sección 4.11 de
 * {@code arquitectura-facturacion-electronica-cr.md}.
 */
class ClienteServiceTest {

    private ClienteRepository clienteRepository;
    private ClienteService clienteService;

    @BeforeEach
    void configurar() {
        clienteRepository = mock(ClienteRepository.class);
        clienteService = new ClienteService(clienteRepository);
        when(clienteRepository.findByNumeroIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CrearClienteRequest requestValido(String tipo, String numero) {
        return new CrearClienteRequest(
                "Cliente de prueba", tipo, numero, null, null, null, null, null, null, null, null);
    }

    @ParameterizedTest
    @CsvSource({
            "01,107890123",
            "02,3101123456",
            "03,123456789012",
            "04,1234567890"
    })
    void crearConIdentificacionValidaPorCadaTipoSeCrea(String tipo, String numero) {
        ClienteResponse respuesta = clienteService.crear(requestValido(tipo, numero));

        assertThat(respuesta.tipoIdentificacion()).isEqualTo(tipo);
        assertThat(respuesta.numeroIdentificacion()).isEqualTo(numero);
        verify(clienteRepository).saveAndFlush(any());
    }

    @Test
    void crearConLongitudIncorrectaParaElTipoSeRechazaYNoPersisteNada() {
        assertThatThrownBy(() -> clienteService.crear(requestValido("01", "123")))
                .isInstanceOf(IdentificacionInvalidaException.class);
        verify(clienteRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConIdentificacionDuplicadaSeRechazaYNoAlteraLaExistente() {
        Cliente existente = new Cliente();
        existente.setNombre("Ya existente");
        when(clienteRepository.findByNumeroIdentificacion("107890123")).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> clienteService.crear(requestValido("01", "107890123")))
                .isInstanceOf(ClienteDuplicadoException.class);

        verify(clienteRepository, never()).saveAndFlush(any());
        assertThat(existente.getNombre()).isEqualTo("Ya existente");
    }

    @Test
    void crearConUbicacionParcialSeRechazaConExcepcionLimpiaNoConCrashDeBaseDeDatos() {
        CrearClienteRequest request = new CrearClienteRequest(
                "Cliente de prueba", "01", "107890123", null,
                "1", "01", null, "Del parque 200m norte", null, null, null);

        assertThatThrownBy(() -> clienteService.crear(request))
                .isInstanceOf(UbicacionInvalidaException.class);
        verify(clienteRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConOtrasSenasMenorAlMinimoSeRechaza() {
        CrearClienteRequest request = new CrearClienteRequest(
                "Cliente de prueba", "01", "107890123", null,
                "1", "01", "01", "1m", null, null, null);

        assertThatThrownBy(() -> clienteService.crear(request))
                .isInstanceOf(UbicacionInvalidaException.class);
    }

    @Test
    void crearConUbicacionCompletaValida() {
        CrearClienteRequest request = new CrearClienteRequest(
                "Cliente de prueba", "01", "107890123", null,
                "1", "01", "01", "Del parque 200m norte", null, null, null);

        ClienteResponse respuesta = clienteService.crear(request);

        assertThat(respuesta.codigoProvincia()).isEqualTo("1");
        assertThat(respuesta.otrasSenas()).isEqualTo("Del parque 200m norte");
    }

    @Test
    void actualizarClientePorIdDejaLosNoIncluidosIntactos() {
        Cliente existente = new Cliente();
        existente.setNombre("Nombre original");
        existente.setTipoIdentificacion("01");
        existente.setNumeroIdentificacion("107890123");
        when(clienteRepository.findById(any())).thenReturn(Optional.of(existente));

        ActualizarClienteRequest request = new ActualizarClienteRequest(
                "Nombre actualizado", null, null, null, null, null, null, null, null, null, null);

        ClienteResponse respuesta = clienteService.actualizar(UUID.randomUUID(), request);

        assertThat(respuesta.nombre()).isEqualTo("Nombre actualizado");
        assertThat(respuesta.numeroIdentificacion()).isEqualTo("107890123");
    }

    @Test
    void actualizarClienteInexistenteLanzaClienteNoEncontrado() {
        when(clienteRepository.findById(any())).thenReturn(Optional.empty());

        ActualizarClienteRequest request = new ActualizarClienteRequest(
                "X", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> clienteService.actualizar(UUID.randomUUID(), request))
                .isInstanceOf(ClienteNoEncontradoException.class);
    }
}
