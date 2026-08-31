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

import cr.ac.fractall.reportes.dto.ReporteFlujoCajaResponse;
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
 * <p>Solo el endpoint JSON llega en esta PR (5 de 7) -- los endpoints {@code /excel} y
 * {@code /pdf} llegan en las PR6/PR7 de este cambio, ver {@code sdd/reporte-flujo-caja/tasks}.
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
}
