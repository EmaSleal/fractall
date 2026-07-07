package cr.ac.fractall.catalogo.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.catalogo.ClienteExoneracion;
import cr.ac.fractall.catalogo.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.ClienteRepository;
import cr.ac.fractall.catalogo.dto.ClienteExoneracionResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteExoneracionRequest;

/**
 * Alta/consulta/desactivación de {@code cliente_exoneracion} (Fase 6, sección 4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- data maestra ligada al cliente; su
 * *consumo* dentro de la creación de una {@code linea_factura} queda para la Fase 7 (ver
 * {@code V8__cliente_exoneracion.sql}).
 *
 * <p>{@code empresa_id} nunca se asigna manualmente -- ver el javadoc de {@code ProductoService}
 * (misma nota, {@code ClienteExoneracion} también extiende {@code TenantAwareEntity}).
 */
@Service
public class ClienteExoneracionService {

    /** Catálogo oficial de 12 códigos, sección 4.15.1 del documento de arquitectura. */
    private static final Set<String> CODIGOS_TIPO_DOCUMENTO = Set.of(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "99");
    private static final String TIPO_DOCUMENTO_OTROS = "99";

    private final ClienteExoneracionRepository clienteExoneracionRepository;
    private final ClienteRepository clienteRepository;

    public ClienteExoneracionService(
            ClienteExoneracionRepository clienteExoneracionRepository,
            ClienteRepository clienteRepository) {
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.clienteRepository = clienteRepository;
    }

    @Transactional
    public ClienteExoneracionResponse crear(UUID clienteId, CrearClienteExoneracionRequest request) {
        // findById ya filtra por @TenantId -- un clienteId de otro tenant resuelve vacío, que
        // se trata igual que "cliente no encontrado" (mismo principio confirmado en la Fase 5
        // para Empresa/findById, ver el javadoc de ClienteRepository).
        if (clienteRepository.findById(clienteId).isEmpty()) {
            throw new ClienteNoEncontradoException(clienteId);
        }
        validarTipoDocumento(request.tipoDocumento(), request.nombreInstitucionOtros());

        if (clienteExoneracionRepository.findByClienteIdAndNumeroDocumento(clienteId, request.numeroDocumento())
                .isPresent()) {
            throw new ClienteExoneracionDuplicadaException(request.numeroDocumento());
        }

        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setClienteId(clienteId);
        exoneracion.setTipoDocumento(request.tipoDocumento());
        exoneracion.setNumeroDocumento(request.numeroDocumento());
        exoneracion.setNombreInstitucion(request.nombreInstitucion());
        exoneracion.setNumeroArticulo(request.numeroArticulo());
        exoneracion.setInciso(request.inciso());
        exoneracion.setNombreInstitucionOtros(request.nombreInstitucionOtros());
        exoneracion.setFechaEmision(request.fechaEmision());
        exoneracion.setFechaVencimiento(request.fechaVencimiento());
        exoneracion.setPorcentajeExoneracion(request.porcentajeExoneracion());
        exoneracion.setActivo(true);

        LocalDateTime ahora = LocalDateTime.now();
        exoneracion.setCreateDate(ahora);
        exoneracion.setUpdateDate(ahora);

        clienteExoneracionRepository.saveAndFlush(exoneracion);
        return ClienteExoneracionResponse.desde(exoneracion);
    }

    public List<ClienteExoneracionResponse> listarPorCliente(UUID clienteId, boolean soloVigentes) {
        if (clienteRepository.findById(clienteId).isEmpty()) {
            throw new ClienteNoEncontradoException(clienteId);
        }
        return clienteExoneracionRepository.findByClienteId(clienteId).stream()
                .filter(exoneracion -> !soloVigentes || estaVigente(exoneracion))
                .map(ClienteExoneracionResponse::desde)
                .toList();
    }

    @Transactional
    public ClienteExoneracionResponse desactivar(UUID id) {
        ClienteExoneracion exoneracion = clienteExoneracionRepository.findById(id)
                .orElseThrow(() -> new ClienteExoneracionNoEncontradaException(id));
        exoneracion.setActivo(false);
        exoneracion.setUpdateDate(LocalDateTime.now());
        clienteExoneracionRepository.saveAndFlush(exoneracion);
        return ClienteExoneracionResponse.desde(exoneracion);
    }

    /**
     * Chequeo de solo lectura -- NO es un trigger de motor todavía (eso es trabajo de la Fase
     * 7, {@code fn_validar_exoneracion_vigente}); esta bandera solo se vuelve determinante una
     * vez que la Fase 7 la consulte al armar una {@code linea_factura}.
     */
    public static boolean estaVigente(ClienteExoneracion exoneracion) {
        if (!exoneracion.isActivo()) {
            return false;
        }
        LocalDateTime vencimiento = exoneracion.getFechaVencimiento();
        return vencimiento == null || !vencimiento.isBefore(LocalDateTime.now());
    }

    private void validarTipoDocumento(String tipoDocumento, String nombreInstitucionOtros) {
        if (tipoDocumento == null || !CODIGOS_TIPO_DOCUMENTO.contains(tipoDocumento)) {
            throw new TipoDocumentoExoneracionInvalidoException(
                    "Tipo de documento de exoneración inválido: " + tipoDocumento);
        }
        if (TIPO_DOCUMENTO_OTROS.equals(tipoDocumento)
                && (nombreInstitucionOtros == null || nombreInstitucionOtros.isBlank())) {
            throw new TipoDocumentoExoneracionInvalidoException(
                    "nombreInstitucionOtros es obligatorio cuando tipoDocumento = '99'");
        }
    }
}
