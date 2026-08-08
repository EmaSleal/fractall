package cr.ac.fractall.catalogo.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.modelo.TipoIdentificacion;
import cr.ac.fractall.catalogo.dto.ActualizarClienteRequest;
import cr.ac.fractall.catalogo.dto.ClienteResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.shared.PaginaResponse;

/**
 * Alta/edición de {@code cliente} con validación de identificación por tipo y del bloque
 * todo-o-nada de ubicación (Fase 6, sección 4.11 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>{@code empresa_id} nunca se asigna manualmente -- ver el javadoc de {@code ProductoService}
 * (misma nota, {@code Cliente} también extiende {@code TenantAwareEntity}).
 */
@Service
public class ClienteService {

    private final ClienteRepository clienteRepository;
    private final UbicacionValidator ubicacionValidator;

    public ClienteService(ClienteRepository clienteRepository, UbicacionValidator ubicacionValidator) {
        this.clienteRepository = clienteRepository;
        this.ubicacionValidator = ubicacionValidator;
    }

    @Transactional
    public ClienteResponse crear(CrearClienteRequest request) {
        validarIdentificacion(request.tipoIdentificacion(), request.numeroIdentificacion());
        ubicacionValidator.validar(request.codigoProvincia(), request.canton(), request.distrito(), request.otrasSenas());

        if (clienteRepository.findByNumeroIdentificacion(request.numeroIdentificacion()).isPresent()) {
            throw new ClienteDuplicadoException(request.numeroIdentificacion());
        }

        Cliente cliente = new Cliente();
        cliente.setNombre(request.nombre());
        cliente.setTipoIdentificacion(request.tipoIdentificacion());
        cliente.setNumeroIdentificacion(request.numeroIdentificacion());
        cliente.setCodigoActividad(request.codigoActividad());
        cliente.setCodigoProvincia(request.codigoProvincia());
        cliente.setCanton(request.canton());
        cliente.setDistrito(request.distrito());
        cliente.setOtrasSenas(request.otrasSenas());
        cliente.setTelefono(request.telefono());
        cliente.setEmail(request.email());
        cliente.setRequiereFacturaElectronica(
                request.requiereFacturaElectronica() == null || request.requiereFacturaElectronica());

        LocalDateTime ahora = LocalDateTime.now();
        cliente.setCreateDate(ahora);
        cliente.setUpdateDate(ahora);

        clienteRepository.saveAndFlush(cliente);
        return ClienteResponse.desde(cliente);
    }

    @Transactional
    public ClienteResponse actualizar(UUID id, ActualizarClienteRequest request) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(() -> new ClienteNoEncontradoException(id));

        String tipoResultante = valorONulo(request.tipoIdentificacion(), cliente.getTipoIdentificacion());
        String numeroResultante = valorONulo(request.numeroIdentificacion(), cliente.getNumeroIdentificacion());
        if (request.tipoIdentificacion() != null || request.numeroIdentificacion() != null) {
            validarIdentificacion(tipoResultante, numeroResultante);
            if (!numeroResultante.equals(cliente.getNumeroIdentificacion())
                    && clienteRepository.findByNumeroIdentificacion(numeroResultante).isPresent()) {
                throw new ClienteDuplicadoException(numeroResultante);
            }
        }

        String provinciaResultante = valorONulo(request.codigoProvincia(), cliente.getCodigoProvincia());
        String cantonResultante = valorONulo(request.canton(), cliente.getCanton());
        String distritoResultante = valorONulo(request.distrito(), cliente.getDistrito());
        String otrasSenasResultante = valorONulo(request.otrasSenas(), cliente.getOtrasSenas());
        if (request.codigoProvincia() != null || request.canton() != null
                || request.distrito() != null || request.otrasSenas() != null) {
            ubicacionValidator.validar(provinciaResultante, cantonResultante, distritoResultante, otrasSenasResultante);
        }

        aplicarSiNoEsNulo(request.nombre(), cliente::setNombre);
        aplicarSiNoEsNulo(request.tipoIdentificacion(), cliente::setTipoIdentificacion);
        aplicarSiNoEsNulo(request.numeroIdentificacion(), cliente::setNumeroIdentificacion);
        aplicarSiNoEsNulo(request.codigoActividad(), cliente::setCodigoActividad);
        aplicarSiNoEsNulo(request.codigoProvincia(), cliente::setCodigoProvincia);
        aplicarSiNoEsNulo(request.canton(), cliente::setCanton);
        aplicarSiNoEsNulo(request.distrito(), cliente::setDistrito);
        aplicarSiNoEsNulo(request.otrasSenas(), cliente::setOtrasSenas);
        aplicarSiNoEsNulo(request.telefono(), cliente::setTelefono);
        aplicarSiNoEsNulo(request.email(), cliente::setEmail);
        aplicarSiNoEsNulo(request.requiereFacturaElectronica(), cliente::setRequiereFacturaElectronica);
        cliente.setUpdateDate(LocalDateTime.now());

        clienteRepository.saveAndFlush(cliente);
        return ClienteResponse.desde(cliente);
    }

    @Transactional(readOnly = true)
    public PaginaResponse<ClienteResponse> listar(String q, UUID cursor, int limit) {
        List<Cliente> filas = clienteRepository.buscar(q, cursor, limit + 1);
        boolean hayMas = filas.size() > limit;
        List<Cliente> pagina = hayMas ? filas.subList(0, limit) : filas;
        UUID next = hayMas ? pagina.get(pagina.size() - 1).getId() : null;
        return new PaginaResponse<>(pagina.stream().map(ClienteResponse::desde).toList(), next);
    }

    @Transactional(readOnly = true)
    public ClienteResponse obtener(UUID id) {
        return clienteRepository.findById(id)
                .map(ClienteResponse::desde)
                .orElseThrow(() -> new ClienteNoEncontradoException(id));
    }

    private void validarIdentificacion(String tipoIdentificacion, String numeroIdentificacion) {
        TipoIdentificacion tipo;
        try {
            tipo = TipoIdentificacion.fromCodigo(tipoIdentificacion);
        } catch (IllegalArgumentException excepcion) {
            throw new IdentificacionInvalidaException(
                    "Tipo de identificación inválido: " + tipoIdentificacion);
        }
        if (!tipo.validarNumero(numeroIdentificacion)) {
            throw new IdentificacionInvalidaException(
                    "El número '" + numeroIdentificacion + "' no es válido para el tipo " + tipo.getDescripcion());
        }
    }

    private static String valorONulo(String nuevo, String actual) {
        return nuevo != null ? nuevo : actual;
    }

    private static <T> void aplicarSiNoEsNulo(T valor, Consumer<T> setter) {
        if (valor != null) {
            setter.accept(valor);
        }
    }
}
