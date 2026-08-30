package cr.ac.fractall.reportes.controlador;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.reportes.dto.ReporteIvaResponse;
import cr.ac.fractall.reportes.export.ReporteIvaExcelWriter;
import cr.ac.fractall.reportes.export.ReporteIvaPdfWriter;
import cr.ac.fractall.reportes.servicio.ReporteIvaService;
import jakarta.validation.constraints.NotNull;

/**
 * {@code GET /reportes/iva} (Release 3 / Fase D, PR4, ver el diseño). Corre detrás de la misma
 * cadena de filtros que cualquier otro endpoint autenticado ({@code JwtAuthenticationFilter} /
 * {@code JwtTenantFilter} -- ver {@code SecurityConfig}, {@code anyRequest().authenticated()}):
 * no requirió ningún cambio en {@code SecurityConfig} para exponerse.
 *
 * <p>Delegación de una sola línea a {@link ReporteIvaService#generar} -- {@code
 * RangoFechasInvalidaException} se propaga al {@code GlobalExceptionHandler} sin try/catch aquí
 * (misma disciplina que {@code FacturaController}, ver su javadoc). {@code desde}/{@code hasta}
 * son OBLIGATORIOS (sin {@code required = false}) -- eso es lo que permite el theta-join JPQL de
 * {@code ReporteIvaRepository} en vez de SQL nativo (ver el diseño, decisión A3).
 */
@Tag(name = "Reportes", description = "Reportes fiscales agregados por período")
@Validated
@RestController
@RequestMapping("/reportes")
public class ReporteIvaController {

    private final ReporteIvaService reporteIvaService;

    public ReporteIvaController(ReporteIvaService reporteIvaService) {
        this.reporteIvaService = reporteIvaService;
    }

    @Operation(summary = "Reporte de débito fiscal de IVA por período")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/iva", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReporteIvaResponse> obtener(
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(reporteIvaService.generar(desde, hasta));
    }

    /**
     * Genera el mismo reporte que {@link #obtener} como XLSX ({@code Resumen}/{@code Detalle}, ver
     * {@link ReporteIvaExcelWriter}). Delegación de una sola línea, sin try/catch, misma disciplina
     * que {@link #obtener} -- {@code RangoFechasInvalidaException} se propaga igual.
     */
    @Operation(summary = "Reporte de débito fiscal de IVA por período (Excel)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(
            path = "/iva/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> excel(
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate hasta) {
        byte[] contenido = ReporteIvaExcelWriter.generar(reporteIvaService.generar(desde, hasta));
        String nombreArchivo = "reporte-iva_" + desde + "_" + hasta + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombreArchivo + "\"")
                .body(contenido);
    }

    /**
     * Genera el mismo reporte que {@link #obtener} como PDF (página 1 Resumen, página 2+ Detalle
     * con encabezado repetible, ver {@link ReporteIvaPdfWriter}). Delegación de una sola línea, sin
     * try/catch, misma disciplina que {@link #obtener}/{@link #excel}.
     */
    @Operation(summary = "Reporte de débito fiscal de IVA por período (PDF)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/iva/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate hasta) {
        byte[] contenido = ReporteIvaPdfWriter.generar(reporteIvaService.generar(desde, hasta));
        String nombreArchivo = "reporte-iva_" + desde + "_" + hasta + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombreArchivo + "\"")
                .body(contenido);
    }
}
