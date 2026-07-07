package cr.ac.fractall.catalogo.controlador;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.dto.ActualizarClienteRequest;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.catalogo.servicio.ClienteDuplicadoException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ClienteService;
import cr.ac.fractall.catalogo.servicio.IdentificacionInvalidaException;
import cr.ac.fractall.catalogo.servicio.UbicacionInvalidaException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /catalogo/clientes} y {@code PATCH /catalogo/clientes/{id}} (Fase 6, sección 4.11
 * de {@code arquitectura-facturacion-electronica-cr.md}). Mismo patrón de resolución de
 * {@code empresaId} vía {@code TenantContext} que {@code ProductoController} -- ver su javadoc.
 */
@RestController
@RequestMapping("/catalogo/clientes")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @PostMapping
    public ResponseEntity<?> crear(@Valid @RequestBody CrearClienteRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(clienteService.crear(request));
        } catch (IdentificacionInvalidaException | UbicacionInvalidaException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ClienteDuplicadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> actualizar(@PathVariable UUID id, @Valid @RequestBody ActualizarClienteRequest request) {
        try {
            return ResponseEntity.ok(clienteService.actualizar(id, request));
        } catch (IdentificacionInvalidaException | UbicacionInvalidaException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ClienteDuplicadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
        } catch (ClienteNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        }
    }
}
