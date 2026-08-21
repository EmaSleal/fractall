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

import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.ComprobanteEmisionService;
import cr.ac.fractall.facturacion.servicio.NotaCreditoDebitoService;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /notas-credito} (Release 2 / Fase B, ver diseño D-E/D-G). Mismo patrón de 2
 * llamadas explícitas que {@code FacturaController#crear} (decisión D-B2): {@code
 * notaCreditoDebitoService#crearNotaCredito} hace commit de su transacción, y SOLO DESPUÉS este
 * controlador invoca {@code comprobanteEmisionService#procesarXmlYEnvio} como una segunda llamada
 * separada, no dentro de esa transacción. Sin try/catch -- las excepciones de dominio
 * (404/400/409) se manejan globalmente en {@code GlobalExceptionHandler}.
 *
 * <p>Lecturas (detalle, PDF, XML) NO se duplican aquí: una NC es una fila {@code factura} más, y
 * los endpoints existentes de {@code FacturaController} (bajo {@code /facturas/{id}/...}) ya la
 * sirven (ver el diseño D-G).
 */
@Tag(name = "Notas de crédito", description = "Emisión de notas de crédito electrónicas")
@Validated
@RestController
@RequestMapping("/notas-credito")
public class NotaCreditoController {

    private final NotaCreditoDebitoService notaCreditoDebitoService;
    private final ComprobanteEmisionService comprobanteEmisionService;

    public NotaCreditoController(
            NotaCreditoDebitoService notaCreditoDebitoService,
            ComprobanteEmisionService comprobanteEmisionService) {
        this.notaCreditoDebitoService = notaCreditoDebitoService;
        this.comprobanteEmisionService = comprobanteEmisionService;
    }

    @Operation(summary = "Emitir nota de crédito electrónica")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Nota de crédito emitida exitosamente",
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
    public ResponseEntity<FacturaResponse> crear(@Valid @RequestBody CrearNotaCreditoRequest request) {
        FacturaResponse response = notaCreditoDebitoService.crearNotaCredito(request);
        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
