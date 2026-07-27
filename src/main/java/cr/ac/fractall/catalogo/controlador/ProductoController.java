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

import cr.ac.fractall.catalogo.dto.ActualizarProductoRequest;
import cr.ac.fractall.catalogo.dto.CrearProductoRequest;
import cr.ac.fractall.catalogo.dto.ProductoResponse;
import cr.ac.fractall.catalogo.servicio.CabysSinImpuestoException;
import cr.ac.fractall.catalogo.servicio.CodigoCabysInvalidoException;
import cr.ac.fractall.catalogo.servicio.HaciendaNoDisponibleException;
import cr.ac.fractall.catalogo.servicio.ProductoDuplicadoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoService;
import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import cr.ac.fractall.shared.PaginaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code GET /catalogo/cabys}, {@code GET /catalogo/productos},
 * {@code GET /catalogo/productos/{id}}, {@code POST /catalogo/productos} y
 * {@code PATCH /catalogo/productos/{id}} (Fase 6, sección 4.10 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Corre detrás de un access token normal ya autenticado por
 * {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} (regla
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}, sin cambios en esta fase) --
 * {@code empresaId} nunca llega por path variable ni cuerpo de la solicitud, lo resuelve
 * {@code ProductoService} internamente vía {@code TenantContext} (mismo patrón de
 * {@code EmpresaController}, ver su javadoc).
 *
 * <p>{@code @Validated} a nivel de clase es necesario para que las restricciones
 * {@code @Min}/{@code @Max} de los {@code @RequestParam} sean evaluadas por Bean Validation.
 */
@Tag(name = "Catálogo — Productos", description = "Gestión de productos y búsqueda CABYS")
@Validated
@RestController
@RequestMapping("/catalogo")
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @Operation(summary = "Buscar códigos CABYS")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/cabys")
    public ResponseEntity<CabysBusquedaDTO> buscarCabys(
            @RequestParam String q,
            @RequestParam(required = false) Integer top) {
        return ResponseEntity.ok(productoService.buscarCabys(q, top));
    }

    @Operation(summary = "Listar productos del catálogo")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/productos")
    public ResponseEntity<PaginaResponse<ProductoResponse>> listar(
            @RequestParam(defaultValue = "true") Boolean activo,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(productoService.listar(activo, cursor, limit));
    }

    @Operation(summary = "Obtener producto por id")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/productos/{id}")
    public ResponseEntity<?> obtener(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(productoService.obtener(id));
        } catch (ProductoNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @Operation(summary = "Crear producto en el catálogo")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/productos")
    public ResponseEntity<?> crear(@Valid @RequestBody CrearProductoRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(productoService.crear(request));
        } catch (CodigoCabysInvalidoException | CabysSinImpuestoException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ProductoDuplicadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
        } catch (HaciendaNoDisponibleException excepcion) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @Operation(summary = "Actualizar producto del catálogo")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/productos/{id}")
    public ResponseEntity<?> actualizar(@PathVariable UUID id, @Valid @RequestBody ActualizarProductoRequest request) {
        try {
            return ResponseEntity.ok(productoService.actualizar(id, request));
        } catch (CodigoCabysInvalidoException | CabysSinImpuestoException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ProductoDuplicadoException excepcion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MensajeResponse(excepcion.getMessage()));
        } catch (ProductoNoEncontradoException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        } catch (HaciendaNoDisponibleException excepcion) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
        }
    }
}
