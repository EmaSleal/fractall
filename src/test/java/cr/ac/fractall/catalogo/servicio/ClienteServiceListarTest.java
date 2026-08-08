package cr.ac.fractall.catalogo.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.catalogo.dto.ClienteResponse;
import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.shared.PaginaResponse;

/**
 * Prueba unitaria de {@link ClienteService#listar} y {@link ClienteService#obtener} con
 * {@code ClienteRepository} mockeado (sin contexto de Spring, sin base de datos real).
 * Cubre paginación keyset y filtro por texto q. Spec: FR-3, FR-4.
 */
class ClienteServiceListarTest {

    private ClienteRepository clienteRepository;
    private ClienteService clienteService;

    @BeforeEach
    void configurar() {
        clienteRepository = mock(ClienteRepository.class);
        // listar/obtener nunca invocan UbicacionValidator -- un mock basta, no hace falta
        // stubear DistritoRepository (ver ClienteServiceTest para los tests que sí ejercitan la
        // validación de ubicación).
        clienteService = new ClienteService(clienteRepository, mock(UbicacionValidator.class));
    }

    /** Sets the id field on an EntidadBase (no-setter by design) via reflection. */
    private static void setId(Object entity, UUID id) throws Exception {
        Field campo = cr.ac.fractall.shared.EntidadBase.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(entity, id);
    }

    private static Cliente clienteConId(int index) throws Exception {
        Cliente c = new Cliente();
        setId(c, UUID.fromString(String.format("00000000-0000-0000-0001-%012d", index)));
        c.setNombre("Cliente " + index);
        c.setTipoIdentificacion("01");
        c.setNumeroIdentificacion(String.format("1%08d", index));
        c.setRequiereFacturaElectronica(true);
        return c;
    }

    private static List<Cliente> generarClientes(int cantidad) throws Exception {
        List<Cliente> lista = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            lista.add(clienteConId(i));
        }
        return lista;
    }

    @Test
    void listarSinFiltroConMenosItemsQueLimitRetornaNextCursorNull() throws Exception {
        List<Cliente> cincoItems = generarClientes(5);
        when(clienteRepository.buscar(isNull(), isNull(), eq(21))).thenReturn(cincoItems);

        PaginaResponse<ClienteResponse> respuesta = clienteService.listar(null, null, 20);

        assertThat(respuesta.items()).hasSize(5);
        assertThat(respuesta.nextCursor()).isNull();
    }

    @Test
    void listarConMasItemsQueLimitRetornaNextCursorIgualAlIdDelElementoLimit() throws Exception {
        List<Cliente> veintiUnItems = generarClientes(21);
        UUID idDelItem20 = veintiUnItems.get(19).getId();
        when(clienteRepository.buscar(isNull(), isNull(), eq(21))).thenReturn(veintiUnItems);

        PaginaResponse<ClienteResponse> respuesta = clienteService.listar(null, null, 20);

        assertThat(respuesta.items()).hasSize(20);
        assertThat(respuesta.nextCursor()).isEqualTo(idDelItem20);
    }

    @Test
    void listarConFiltroQPasaElParamAlRepositorio() throws Exception {
        List<Cliente> resultado = generarClientes(1);
        when(clienteRepository.buscar(eq("tico"), isNull(), eq(21))).thenReturn(resultado);

        PaginaResponse<ClienteResponse> respuesta = clienteService.listar("tico", null, 20);

        assertThat(respuesta.items()).hasSize(1);
        verify(clienteRepository).buscar(eq("tico"), isNull(), eq(21));
    }

    @Test
    void obtenerConIdInexistenteLanzaClienteNoEncontradoException() {
        UUID idDesconocido = UUID.randomUUID();
        when(clienteRepository.findById(idDesconocido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clienteService.obtener(idDesconocido))
                .isInstanceOf(ClienteNoEncontradoException.class);
    }
}
