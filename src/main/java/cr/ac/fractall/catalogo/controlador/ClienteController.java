package cr.ac.fractall.catalogo.controlador;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.dto.ActualizarClienteRequest;
import cr.ac.fractall.catalogo.dto.ClienteResponse;
import cr.ac.fractall.catalogo.dto.CrearClienteRequest;
import cr.ac.fractall.catalogo.servicio.ClienteDuplicadoException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ClienteService;
import cr.ac.fractall.catalogo.servicio.IdentificacionInvalidaException;
import cr.ac.fractall.catalogo.servicio.UbicacionInvalidaException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.shared.PaginaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code GET /catalogo/clientes}, {@code GET /catalogo/clientes/{id}},
 * {@code POST /catalogo/clientes} y {@code PATCH /catalogo/clientes/{id}} (Fase 6, sección 4.11
 * de {@code arquitectura-facturacion-electronica-cr.md}). Mismo patrón de resolución de
 * {@code empresaId} vía {@code TenantContext} que {@code ProductoController} -- ver su javadoc.
 *
 * <p>{@code @Validated} a nivel de clase es necesario para que las restricciones
 * {@code @Min}/{@code @Max} de los {@code @RequestParam} sean evaluadas por Bean Validation.
 */
@Tag(name = "Catálogo — Clientes", description = "Gestión de clientes")
@Validated
@RestController
@RequestMapping("/catalogo/clientes")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @Operation(summary = "Listar clientes")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<PaginaResponse<ClienteResponse>> listar(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(clienteService.listar(q, cursor, limit));
    }

    @Operation(summary = "Obtener cliente por id")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<?> obtener(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(clienteService.obtener(id));
        } catch (ClienteNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @Operation(summary = "Crear cliente")
    @SecurityRequirement(name = "bearerAuth")
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

    @Operation(summary = "Actualizar cliente")
    @SecurityRequirement(name = "bearerAuth")
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
