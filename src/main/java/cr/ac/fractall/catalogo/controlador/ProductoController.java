package cr.ac.fractall.catalogo.controlador;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import cr.ac.fractall.catalogo.servicio.CabysSinImpuestoException;
import cr.ac.fractall.catalogo.servicio.CodigoCabysInvalidoException;
import cr.ac.fractall.catalogo.servicio.HaciendaNoDisponibleException;
import cr.ac.fractall.catalogo.servicio.ProductoDuplicadoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoService;
import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code GET /catalogo/cabys}, {@code POST /catalogo/productos} y
 * {@code PATCH /catalogo/productos/{id}} (Fase 6, sección 4.10 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Corre detrás de un access token normal ya autenticado por
 * {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} (regla
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}, sin cambios en esta fase) --
 * {@code empresaId} nunca llega por path variable ni cuerpo de la solicitud, lo resuelve
 * {@code ProductoService} internamente vía {@code TenantContext} (mismo patrón de
 * {@code EmpresaController}, ver su javadoc).
 */
@RestController
@RequestMapping("/catalogo")
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @GetMapping("/cabys")
    public ResponseEntity<CabysBusquedaDTO> buscarCabys(
            @RequestParam String q,
            @RequestParam(required = false) Integer top) {
        return ResponseEntity.ok(productoService.buscarCabys(q, top));
    }

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
