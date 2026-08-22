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

import cr.ac.fractall.facturacion.dto.CrearTiqueteRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.ComprobanteEmisionService;
import cr.ac.fractall.facturacion.servicio.TiqueteService;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /tiquetes} (Release 2 / Fase C, ver {@code docs/plan-fases-release-2.md}). Mismo
 * patrón de 2 llamadas explícitas que {@code NotaCreditoController}/{@code NotaDebitoController}
 * (decisión D-B2 de Fase B): {@code tiqueteService#crear} hace commit de su transacción, y SOLO
 * DESPUÉS este controlador invoca {@code comprobanteEmisionService#procesarXmlYEnvio} como una
 * segunda llamada separada, no dentro de esa transacción. Sin try/catch -- las excepciones de
 * dominio (404/400) se manejan globalmente en {@code GlobalExceptionHandler}.
 *
 * <p>Lecturas (detalle, PDF, XML) NO se duplican aquí: un Tiquete es una fila {@code factura} más,
 * y los endpoints existentes de {@code FacturaController} (bajo {@code /facturas/{id}/...}) ya la
 * sirven -- mismo principio que NC/ND.
 */
@Tag(name = "Tiquetes", description = "Emisión de tiquetes electrónicos")
@Validated
@RestController
@RequestMapping("/tiquetes")
public class TiqueteController {

    private final TiqueteService tiqueteService;
    private final ComprobanteEmisionService comprobanteEmisionService;

    public TiqueteController(
            TiqueteService tiqueteService,
            ComprobanteEmisionService comprobanteEmisionService) {
        this.tiqueteService = tiqueteService;
        this.comprobanteEmisionService = comprobanteEmisionService;
    }

    @Operation(operationId = "crearTiquete", summary = "Emitir tiquete electrónico", description = "El receptor (clienteId) es opcional -- un Tiquete puede emitirse sin cliente identificado (venta de mostrador).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tiquete emitido exitosamente",
            content = @Content(schema = @Schema(implementation = FacturaResponse.class))),
        @ApiResponse(responseCode = "400", description = "Error de validación o regla de negocio",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
        @ApiResponse(responseCode = "404", description = "Cliente referenciado no encontrado",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<FacturaResponse> crear(@Valid @RequestBody CrearTiqueteRequest request) {
        FacturaResponse response = tiqueteService.crear(request);
        comprobanteEmisionService.procesarXmlYEnvio(response.comprobanteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
