package cr.ac.fractall.facturacion.controlador;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.ComprobanteEmisionService;
import cr.ac.fractall.facturacion.servicio.NotaCreditoDebitoService;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /notas-debito} (Release 2 / Fase B, ver diseño D-E/D-G). Imagen especular de
 * {@link NotaCreditoController} -- mismo patrón de 2 llamadas explícitas (decisión D-B2), sin
 * try/catch. A diferencia de la NC, sus líneas se arman desde catálogo (no hay tope de monto,
 * regla 3 no aplica a ND).
 */
@Tag(name = "Notas de débito", description = "Emisión de notas de débito electrónicas")
@Validated
@RestController
@RequestMapping("/notas-debito")
public class NotaDebitoController {

    private final NotaCreditoDebitoService notaCreditoDebitoService;
    private final ComprobanteEmisionService comprobanteEmisionService;

    public NotaDebitoController(
            NotaCreditoDebitoService notaCreditoDebitoService,
            ComprobanteEmisionService comprobanteEmisionService) {
        this.notaCreditoDebitoService = notaCreditoDebitoService;
        this.comprobanteEmisionService = comprobanteEmisionService;
    }

    @Operation(summary = "Emitir nota de débito electrónica")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Nota de débito emitida exitosamente",
            content = @Content(schema = @Schema(implementation = FacturaResponse.class))),
        @ApiResponse(responseCode = "400", description = "Error de validación o regla de negocio",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
        @ApiResponse(responseCode = "404", description = "Factura de referencia no encontrada",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
        @ApiResponse(responseCode = "409", description = "Estado de la factura de referencia no permite la operación",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<FacturaResponse> crear(@Valid @RequestBody CrearNotaDebitoRequest request) {
        FacturaResponse response = notaCreditoDebitoService.crearNotaDebito(request);
        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
