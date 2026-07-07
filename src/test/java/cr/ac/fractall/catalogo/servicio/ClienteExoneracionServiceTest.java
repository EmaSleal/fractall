package cr.ac.fractall.catalogo.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.dto.ClienteExoneracionResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteExoneracionRequest;

/**
 * Prueba unitaria de {@link ClienteExoneracionService} con los repositorios mockeados (sin
 * contexto de Spring, sin base de datos real) -- sección 4.15/4.15.1 de
 * {@code arquitectura-facturacion-electronica-cr.md}.
 */
class ClienteExoneracionServiceTest {

    private ClienteExoneracionRepository clienteExoneracionRepository;
    private ClienteRepository clienteRepository;
    private ClienteExoneracionService servicio;
    private UUID clienteId;

    @BeforeEach
    void configurar() {
        clienteExoneracionRepository = mock(ClienteExoneracionRepository.class);
        clienteRepository = mock(ClienteRepository.class);
        servicio = new ClienteExoneracionService(clienteExoneracionRepository, clienteRepository);
        clienteId = UUID.randomUUID();
        when(clienteRepository.findById(clienteId)).thenReturn(Optional.of(new Cliente()));
        when(clienteExoneracionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CrearClienteExoneracionRequest request(String tipoDocumento, String nombreInstitucionOtros) {
        return new CrearClienteExoneracionRequest(
                tipoDocumento, "DOC-001", "Municipalidad de San José", "5", null,
                nombreInstitucionOtros, LocalDateTime.now(), null, new BigDecimal("100.00"));
    }

    @Test
    void crearConTipoNo99SeCreaSinExigirNombreInstitucionOtros() {
        ClienteExoneracionResponse respuesta = servicio.crear(clienteId, request("10", null));

        assertThat(respuesta.tipoDocumento()).isEqualTo("10");
        assertThat(respuesta.activo()).isTrue();
        verify(clienteExoneracionRepository).saveAndFlush(any());
    }

    @Test
    void crearConTipo99SinNombreInstitucionOtrosSeRechaza() {
        assertThatThrownBy(() -> servicio.crear(clienteId, request("99", null)))
                .isInstanceOf(TipoDocumentoExoneracionInvalidoException.class);
        verify(clienteExoneracionRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConTipo99YNombreInstitucionOtrosSeCrea() {
        ClienteExoneracionResponse respuesta = servicio.crear(clienteId, request("99", "Comisión Nacional X"));

        assertThat(respuesta.nombreInstitucionOtros()).isEqualTo("Comisión Nacional X");
        verify(clienteExoneracionRepository).saveAndFlush(any());
    }

    @Test
    void crearConTipoDocumentoFueraDelCatalogoSeRechaza() {
        assertThatThrownBy(() -> servicio.crear(clienteId, request("77", null)))
                .isInstanceOf(TipoDocumentoExoneracionInvalidoException.class);
    }

    @Test
    void crearConClienteYNumeroDocumentoDuplicadosSeRechazaSinPersistirNada() {
        when(clienteExoneracionRepository.findByClienteIdAndNumeroDocumento(clienteId, "DOC-001"))
                .thenReturn(Optional.of(new ClienteExoneracion()));

        assertThatThrownBy(() -> servicio.crear(clienteId, request("10", null)))
                .isInstanceOf(ClienteExoneracionDuplicadaException.class);
        verify(clienteExoneracionRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearParaClienteDeOtroTenantSeTrataComoNoEncontrado() {
        UUID clienteDeOtroTenant = UUID.randomUUID();
        when(clienteRepository.findById(clienteDeOtroTenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.crear(clienteDeOtroTenant, request("10", null)))
                .isInstanceOf(ClienteNoEncontradoException.class);
        verify(clienteExoneracionRepository, never()).saveAndFlush(any());
    }

    @Test
    void desactivarCambiaActivoAFalse() {
        ClienteExoneracion existente = new ClienteExoneracion();
        existente.setActivo(true);
        UUID id = UUID.randomUUID();
        when(clienteExoneracionRepository.findById(id)).thenReturn(Optional.of(existente));

        ClienteExoneracionResponse respuesta = servicio.desactivar(id);

        assertThat(respuesta.activo()).isFalse();
        assertThat(existente.isActivo()).isFalse();
    }

    @Test
    void estaVigenteEsFalsaCuandoInactiva() {
        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setActivo(false);
        exoneracion.setFechaVencimiento(null);

        assertThat(ClienteExoneracionService.estaVigente(exoneracion)).isFalse();
    }

    @Test
    void estaVigenteEsFalsaCuandoVencida() {
        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setActivo(true);
        exoneracion.setFechaVencimiento(LocalDateTime.now().minusDays(1));

        assertThat(ClienteExoneracionService.estaVigente(exoneracion)).isFalse();
    }

    @Test
    void estaVigenteEsVerdaderaCuandoActivaSinVencimientoOConVencimientoFuturo() {
        ClienteExoneracion sinVencimiento = new ClienteExoneracion();
        sinVencimiento.setActivo(true);
        sinVencimiento.setFechaVencimiento(null);
        assertThat(ClienteExoneracionService.estaVigente(sinVencimiento)).isTrue();

        ClienteExoneracion vencimientoFuturo = new ClienteExoneracion();
        vencimientoFuturo.setActivo(true);
        vencimientoFuturo.setFechaVencimiento(LocalDateTime.now().plusDays(30));
        assertThat(ClienteExoneracionService.estaVigente(vencimientoFuturo)).isTrue();
    }
}
