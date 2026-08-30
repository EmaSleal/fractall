package cr.ac.fractall.reportes.controlador;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.reportes.dto.ReporteIvaResponse;
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
 *
 * <p>Solo el endpoint JSON en este PR -- {@code /reportes/iva/pdf} y {@code /reportes/iva/excel}
 * llegan en PRs posteriores (Fase 5-7 del plan de tareas).
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
}
