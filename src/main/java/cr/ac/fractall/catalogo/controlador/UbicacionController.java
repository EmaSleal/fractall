package cr.ac.fractall.catalogo.controlador;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.dto.CantonResponse;
import cr.ac.fractall.catalogo.dto.DistritoResponse;
import cr.ac.fractall.catalogo.dto.ProvinciaResponse;
import cr.ac.fractall.catalogo.repositorio.CantonRepository;
import cr.ac.fractall.catalogo.repositorio.DistritoRepository;
import cr.ac.fractall.catalogo.repositorio.ProvinciaRepository;

/**
 * {@code GET /catalogo/ubicacion/provincias}, {@code GET
 * /catalogo/ubicacion/provincias/{codigo}/cantones} y {@code GET
 * /catalogo/ubicacion/cantones/{provinciaCodigo}/{cantonCodigo}/distritos} -- catálogo de
 * ubicación de Costa Rica (V15__catalogo_ubicacion_cr.sql) para alimentar combos en cascada del
 * frontend. Solo lectura: la escritura del catálogo ocurre exclusivamente vía el seed de la
 * migración.
 */
@Tag(name = "Catálogo — Ubicación", description = "Provincias, cantones y distritos de Costa Rica")
@RestController
@RequestMapping("/catalogo/ubicacion")
public class UbicacionController {

    private final ProvinciaRepository provinciaRepository;
    private final CantonRepository cantonRepository;
    private final DistritoRepository distritoRepository;

    public UbicacionController(
            ProvinciaRepository provinciaRepository,
            CantonRepository cantonRepository,
            DistritoRepository distritoRepository) {
        this.provinciaRepository = provinciaRepository;
        this.cantonRepository = cantonRepository;
        this.distritoRepository = distritoRepository;
    }

    @Operation(summary = "Listar provincias")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/provincias")
    public ResponseEntity<List<ProvinciaResponse>> listarProvincias() {
        List<ProvinciaResponse> provincias = provinciaRepository.findAll(Sort.by("codigo")).stream()
                .map(ProvinciaResponse::desde)
                .toList();
        return ResponseEntity.ok(provincias);
    }

    @Operation(summary = "Listar cantones de una provincia")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/provincias/{codigo}/cantones")
    public ResponseEntity<List<CantonResponse>> listarCantones(@PathVariable String codigo) {
        List<CantonResponse> cantones = cantonRepository.findByIdProvinciaCodigoOrderByIdCodigoAsc(codigo).stream()
                .map(CantonResponse::desde)
                .toList();
        return ResponseEntity.ok(cantones);
    }

    @Operation(summary = "Listar distritos de un cantón")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/cantones/{provinciaCodigo}/{cantonCodigo}/distritos")
    public ResponseEntity<List<DistritoResponse>> listarDistritos(
            @PathVariable String provinciaCodigo, @PathVariable String cantonCodigo) {
        List<DistritoResponse> distritos = distritoRepository
                .findByIdProvinciaCodigoAndIdCantonCodigoOrderByIdCodigoAsc(provinciaCodigo, cantonCodigo).stream()
                .map(DistritoResponse::desde)
                .toList();
        return ResponseEntity.ok(distritos);
    }
}
