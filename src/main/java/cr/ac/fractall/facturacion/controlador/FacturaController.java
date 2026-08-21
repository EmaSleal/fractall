package cr.ac.fractall.facturacion.controlador;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.facturacion.dto.FacturaResumenResponse;
import cr.ac.fractall.shared.PaginaResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.pdf.FacturaPdfService;
import cr.ac.fractall.facturacion.servicio.ComprobanteXmlPersistenceService;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /facturas} (Fase 7, secciones 4.9 y 4.12-4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}). Corre detrás de un access token normal ya
 * autenticado por {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} -- {@code empresaId}
 * nunca llega por path variable ni cuerpo de la solicitud, mismo patrón de
 * {@code ProductoController}/{@code ClienteController} (ver su javadoc).
 *
 * <p>Fase 8: DESPUÉS de que {@link FacturaService#crear} ya hizo commit de su transacción, este
 * controlador invoca {@link ComprobanteXmlPersistenceService#generarYPersistirXml} como una
 * SEGUNDA llamada separada -- nunca dentro de la transacción de {@code crear()} (ver el javadoc
 * de esa clase para el porqué). Si esa segunda llamada falla, la excepción se deja propagar sin
 * capturar: la factura y el comprobante ya quedaron persistidos, pero el cliente HTTP recibe un
 * error en vez de un 201 -- riesgo de fallo parcial documentado y aceptado, ver el javadoc de
 * {@code ComprobanteXmlPersistenceService}.
 *
 * <p><b>Release 2 / Fase B (ver diseño D-B2/D-G):</b> el try/catch explícito de {@link #crear}
 * se eliminó -- las 10 excepciones que capturaba ahora se manejan globalmente en
 * {@code GlobalExceptionHandler} (ver su javadoc), con los mismos statuses. La secuencia de 2
 * llamadas ({@code facturaService.crear} → {@code comprobanteXmlPersistenceService.generarYPersistirXml})
 * se mantiene EXPLÍCITA en este controlador (decisión D-B2: no se colapsa en una sola llamada de
 * fachada) -- la misma secuencia de 2 pasos que usarán {@code NotaCreditoController}/
 * {@code NotaDebitoController} a partir de la Fase 3 de este release, vía
 * {@code ComprobanteEmisionService#procesarXmlYEnvio}.
 */
@Tag(name = "Facturas", description = "Emisión, consulta y reenvío de facturas electrónicas")
@Validated
@RestController
@RequestMapping("/facturas")
public class FacturaController {

    private final FacturaService facturaService;
    private final ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;
    private final FacturaPdfService facturaPdfService;

    public FacturaController(
            FacturaService facturaService,
            ComprobanteXmlPersistenceService comprobanteXmlPersistenceService,
            FacturaPdfService facturaPdfService) {
        this.facturaService = facturaService;
        this.comprobanteXmlPersistenceService = comprobanteXmlPersistenceService;
        this.facturaPdfService = facturaPdfService;
    }

    /**
     * Genera el PDF de la factura indicada al vuelo y lo devuelve como {@code application/pdf}.
     * No persiste el PDF ({@code pdf_referencia} permanece null).
     * {@code FacturaNoEncontradaException} se propaga al {@code GlobalExceptionHandler} → 404.
     */
    @Operation(summary = "Descargar PDF de la factura")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> descargarPdf(@PathVariable UUID id) {
        var doc = facturaPdfService.generarPdfPorFacturaId(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.claveNumerica() + ".pdf\"")
                .body(doc.contenido());
    }

    /**
     * Descarga el XML de factura firmado (XAdES-BES) desde OCI para la factura indicada.
     * {@code FacturaNoEncontradaException} → 404; {@code DocumentoNoDisponibleException} → 404
     * cuando la referencia OCI aún es null (estado anterior a FIRMADO).
     */
    @Operation(summary = "Descargar XML de factura firmado")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/{id}/xml/factura", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> descargarXmlFactura(@PathVariable UUID id) {
        var doc = comprobanteXmlPersistenceService.obtenerXmlFactura(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.claveNumerica() + "_factura.xml\"")
                .body(doc.contenido());
    }

    /**
     * Descarga el XML de respuesta de Hacienda desde OCI para la factura indicada.
     * {@code FacturaNoEncontradaException} → 404; {@code DocumentoNoDisponibleException} → 404
     * cuando la referencia OCI aún es null (Hacienda no ha respondido — estado pre-ENVIADO).
     */
    @Operation(summary = "Descargar XML de respuesta de Hacienda")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/{id}/xml/respuesta", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> descargarXmlRespuesta(@PathVariable UUID id) {
        var doc = comprobanteXmlPersistenceService.obtenerXmlRespuestaPorFactura(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.claveNumerica() + "_respuesta.xml\"")
                .body(doc.contenido());
    }

    /**
     * Lista facturas del tenant activo con paginación keyset y filtros opcionales (FR-1).
     * {@code @Validated} en la clase habilita {@code @Min}/{@code @Max} en el parámetro
     * {@code limit}: violations lanzan {@code ConstraintViolationException} → 400 via
     * {@code GlobalExceptionHandler}.
     */
    @Operation(summary = "Listar facturas del tenant activo")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<PaginaResponse<FacturaResumenResponse>> listar(
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String estado,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(facturaService.listar(cursor, clienteId, desde, hasta, estado, limit));
    }

    /**
     * Devuelve el detalle completo de una factura por id (FR-2). {@code FacturaNoEncontradaException}
     * se propaga al {@code GlobalExceptionHandler} → 404; no se necesita try/catch aquí.
     */
    @Operation(summary = "Obtener factura por id")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<FacturaResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(facturaService.obtener(id));
    }

    /**
     * Reenvía a Hacienda un comprobante atascado en FIRMADO, RECHAZADO o ERROR.
     * {@code ComprobanteNoReenviableException} → 409; {@code FacturaNoEncontradaException} → 404.
     * Ambas se propagan al {@code GlobalExceptionHandler} sin try/catch aquí.
     */
    @Operation(summary = "Reenviar factura a Hacienda")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/reenviar")
    public ResponseEntity<FacturaResponse> reenviar(@PathVariable UUID id) {
        return ResponseEntity.ok(facturaService.reenviar(id));
    }

    @Operation(summary = "Emitir factura electrónica")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Factura emitida exitosamente",
            content = @Content(schema = @Schema(implementation = FacturaResponse.class))),
        @ApiResponse(responseCode = "400", description = "Error de validación o regla de negocio",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
        @ApiResponse(responseCode = "404", description = "Recurso referenciado no encontrado",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class))),
        @ApiResponse(responseCode = "503", description = "Configuración de empresa incompleta",
            content = @Content(schema = @Schema(implementation = MensajeResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<FacturaResponse> crear(@Valid @RequestBody CrearFacturaRequest request) {
        FacturaResponse response = facturaService.crear(request);
        comprobanteXmlPersistenceService.generarYPersistirXml(response.comprobanteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
