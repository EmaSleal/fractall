package cr.ac.fractall.catalogo.controlador;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.dto.ClienteExoneracionResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteExoneracionRequest;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionDuplicadaException;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionService;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.TipoDocumentoExoneracionInvalidoException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /catalogo/clientes/{clienteId}/exoneraciones},
 * {@code GET /catalogo/clientes/{clienteId}/exoneraciones} y
 * {@code POST /catalogo/exoneraciones/{id}/desactivar} (Fase 6, sección 4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}). Mismo patrón de resolución de
 * {@code empresaId} vía {@code TenantContext} que {@code ProductoController} -- ver su javadoc.
 */
@RestController
@RequestMapping("/catalogo")
public class ClienteExoneracionController {

    private final ClienteExoneracionService clienteExoneracionService;

    public ClienteExoneracionController(ClienteExoneracionService clienteExoneracionService) {
        this.clienteExoneracionService = clienteExoneracionService;
    }

    @PostMapping("/clientes/{clienteId}/exoneraciones")
    public ResponseEntity<?> crear(
            @PathVariable UUID clienteId,
            @Valid @RequestBody CrearClienteExoneracionRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(clienteExoneracionService.crear(clienteId, request));
        } catch (TipoDocumentoExoneracionInvalidoException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ClienteNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        } catch (ClienteExoneracionDuplicadaException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @GetMapping("/clientes/{clienteId}/exoneraciones")
    public ResponseEntity<?> listarPorCliente(
            @PathVariable UUID clienteId,
            @RequestParam(name = "soloVigentes", defaultValue = "false") boolean soloVigentes) {
        try {
            List<ClienteExoneracionResponse> respuesta =
                    clienteExoneracionService.listarPorCliente(clienteId, soloVigentes);
            return ResponseEntity.ok(respuesta);
        } catch (ClienteNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @PostMapping("/exoneraciones/{id}/desactivar")
    public ResponseEntity<?> desactivar(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(clienteExoneracionService.desactivar(id));
        } catch (ClienteExoneracionNoEncontradaException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        }
    }
}
