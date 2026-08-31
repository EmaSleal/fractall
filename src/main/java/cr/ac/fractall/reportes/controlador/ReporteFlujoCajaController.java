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

import cr.ac.fractall.reportes.dto.ReporteFlujoCajaResponse;
import cr.ac.fractall.reportes.export.ReporteFlujoCajaExcelWriter;
import cr.ac.fractall.reportes.servicio.ReporteFlujoCajaService;
import jakarta.validation.constraints.NotNull;

/**
 * {@code GET /reportes/flujo-caja} (Release 3 / Fase D, Change 2 de 2, PR5 -- ver el diseño obs
 * #918). Mirror byte-for-byte de la forma de {@code ReporteIvaController} (finding 10 del diseño):
 * {@code @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE)} en vez del boceto original de la
 * propuesta. Corre detrás de la misma cadena de filtros que cualquier otro endpoint autenticado
 * ({@code JwtAuthenticationFilter} / {@code JwtTenantFilter} -- ver {@code SecurityConfig},
 * {@code anyRequest().authenticated()}): no requirió ningún cambio en {@code SecurityConfig}.
 *
 * <p>Delegación de una sola línea a {@link ReporteFlujoCajaService#generar} -- {@code
 * RangoFechasInvalidaException} se propaga al {@code GlobalExceptionHandler} sin try/catch aquí
 * (misma disciplina que {@code ReporteIvaController}/{@code FacturaController}).
 *
 * <p>El endpoint {@code /excel} llega en PR6 de este cambio (ver el diseño obs #918 y
 * {@code sdd/reporte-flujo-caja/tasks}, Fase 6) -- {@code /pdf} sigue pendiente para PR7.
 */
@Tag(name = "Reportes", description = "Reportes fiscales agregados por período")
@Validated
@RestController
@RequestMapping("/reportes")
public class ReporteFlujoCajaController {

    private final ReporteFlujoCajaService reporteFlujoCajaService;

    public ReporteFlujoCajaController(ReporteFlujoCajaService reporteFlujoCajaService) {
        this.reporteFlujoCajaService = reporteFlujoCajaService;
    }

    @Operation(summary = "Reporte de flujo de caja (ventas, cobros, cartera pendiente) por período")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/flujo-caja", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReporteFlujoCajaResponse> obtener(
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(reporteFlujoCajaService.generar(desde, hasta));
    }

    /**
     * Genera el mismo reporte que {@link #obtener} como XLSX ({@code Resumen}/{@code
     * DetalleVentas}/{@code DetalleCobros}, ver {@link ReporteFlujoCajaExcelWriter}). Delegación
     * de una sola línea, sin try/catch, misma disciplina que {@link #obtener} y que
     * {@code ReporteIvaController#excel} -- {@code RangoFechasInvalidaException} se propaga igual.
     */
    @Operation(summary = "Reporte de flujo de caja (ventas, cobros, cartera pendiente) por período (Excel)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(
            path = "/flujo-caja/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> excel(
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate desde,
            @RequestParam @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate hasta) {
        byte[] contenido =
                ReporteFlujoCajaExcelWriter.generar(reporteFlujoCajaService.generar(desde, hasta));
        String nombreArchivo = "reporte-flujo-caja_" + desde + "_" + hasta + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombreArchivo + "\"")
                .body(contenido);
    }
}
