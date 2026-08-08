package cr.ac.fractall.catalogo.controlador;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.hacienda.dto.TipoCambioDolarDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;

/**
 * {@code GET /catalogo/tipo-cambio/dolar} -- expone el tipo de cambio del dólar del día
 * (cache-aware, ver el javadoc de {@code HaciendaConsultaServiceImpl}) para que el frontend pueda
 * mostrárselo al usuario antes de que arme una factura en USD.
 *
 * <p>Vive en el paquete {@code catalogo} (no {@code hacienda}) aunque los datos vengan de
 * {@code hacienda} -- mismo criterio que {@code GET /catalogo/cabys} en {@link ProductoController}:
 * los endpoints de referencia/catálogo consumidos por el frontend van bajo {@code /catalogo}, sin
 * importar de qué paquete interno vengan los datos.
 *
 * <p>Sin manejo de excepción propio: {@code TipoCambioNoDisponibleException} ya la resuelve
 * {@code GlobalExceptionHandler} (503).
 */
@Tag(name = "Catálogo — Tipo de cambio", description = "Tipo de cambio del dólar del día")
@RestController
@RequestMapping("/catalogo/tipo-cambio")
public class TipoCambioController {

    private final HaciendaApiService haciendaApiService;

    public TipoCambioController(HaciendaApiService haciendaApiService) {
        this.haciendaApiService = haciendaApiService;
    }

    @Operation(summary = "Consultar el tipo de cambio del dólar del día")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/dolar")
    public ResponseEntity<TipoCambioDolarDTO> consultarDolar() {
        return ResponseEntity.ok(haciendaApiService.consultarTipoCambioDolar());
    }
}
