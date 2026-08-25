package cr.ac.fractall.empresa.controlador;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.CambiarAmbienteRequest;
import cr.ac.fractall.empresa.dto.CargarCertificadoRequest;
import cr.ac.fractall.empresa.dto.ConfigurarCredencialHaciendaRequest;
import cr.ac.fractall.empresa.dto.ConsecutivosResponse;
import cr.ac.fractall.empresa.dto.EmpresaResponse;
import cr.ac.fractall.empresa.dto.FijarConsecutivoRequest;
import cr.ac.fractall.catalogo.servicio.UbicacionInvalidaException;
import cr.ac.fractall.empresa.servicio.AmbienteNoDisponibleException;
import cr.ac.fractall.empresa.servicio.CertificadoInvalidoException;
import cr.ac.fractall.empresa.servicio.EmpresaService;
import cr.ac.fractall.facturacion.servicio.ConsecutivoInvalidoException;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code PATCH /empresa}, {@code POST /empresa/certificado} y
 * {@code POST /empresa/credenciales-hacienda} (Fase 5, sección 4.1, 4.2 y 6.4 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Los 3 endpoints corren detrás de un access token normal ya autenticado por
 * {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} -- ninguno recibe {@code empresaId}
 * por path variable ni por cuerpo de la solicitud (evita IDOR vía manipulación de
 * {@code empresa_id}); lo resuelve internamente {@code EmpresaService} desde
 * {@code TenantContext}.
 */
@Tag(name = "Empresa", description = "Configuración fiscal, certificado y credenciales de Hacienda")
@RestController
@RequestMapping("/empresa")
public class EmpresaController {

    private static final MensajeResponse MENSAJE_SIN_AUTENTICAR =
            new MensajeResponse("No autenticado.");

    private final EmpresaService empresaService;

    public EmpresaController(EmpresaService empresaService) {
        this.empresaService = empresaService;
    }

    @Operation(summary = "Consultar datos fiscales de la empresa activa")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<EmpresaResponse> consultar() {
        return ResponseEntity.ok(empresaService.consultar());
    }

    @Operation(summary = "Actualizar datos fiscales de la empresa activa")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping
    public ResponseEntity<?> actualizarDatosFiscales(
            @Valid @RequestBody ActualizarDatosFiscalesRequest request) {
        try {
            return ResponseEntity.ok(empresaService.actualizarDatosFiscales(request));
        } catch (UbicacionInvalidaException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @Operation(summary = "Cargar certificado digital .p12")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(value = "/certificado", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> cargarCertificado(@Valid @ModelAttribute CargarCertificadoRequest request) {
        if (request.certificado().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MensajeResponse("El archivo .p12 es obligatorio"));
        }
        try {
            byte[] certificadoP12 = request.certificado().getBytes();
            return ResponseEntity.ok(empresaService.cargarCertificado(certificadoP12, request.pin(), request.ambiente()));
        } catch (IOException excepcion) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MensajeResponse("No se pudo leer el archivo .p12 enviado."));
        } catch (CertificadoInvalidoException excepcion) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @Operation(summary = "Configurar credenciales de acceso a Hacienda")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/credenciales-hacienda")
    public ResponseEntity<?> configurarCredencialHacienda(
            @Valid @RequestBody ConfigurarCredencialHaciendaRequest request) {
        Optional<UUID> usuarioId = usuarioIdAutenticado();
        if (usuarioId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        EmpresaResponse respuesta = empresaService.configurarCredencialHacienda(
                request.usuarioHacienda(), request.password(), request.ambiente(), usuarioId.get());
        return ResponseEntity.ok(respuesta);
    }

    @Operation(summary = "Cambiar el ambiente activo de Hacienda (SANDBOX o PRODUCCION)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/ambiente")
    public ResponseEntity<?> cambiarAmbiente(@Valid @RequestBody CambiarAmbienteRequest request) {
        Optional<UUID> usuarioId = usuarioIdAutenticado();
        if (usuarioId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }
        try {
            EmpresaResponse respuesta = empresaService.activarAmbiente(request.ambiente(), usuarioId.get());
            return ResponseEntity.ok(respuesta);
        } catch (AmbienteNoDisponibleException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MensajeResponse(e.getMessage()));
        }
    }

    @Operation(summary = "Consultar los consecutivos actuales por ambiente y tipo de comprobante")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/consecutivos")
    public ResponseEntity<ConsecutivosResponse> consultarConsecutivos() {
        return ResponseEntity.ok(empresaService.consultarConsecutivos());
    }

    @Operation(summary = "Fijar el consecutivo de un tipo de comprobante en un ambiente")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/consecutivos")
    public ResponseEntity<?> fijarConsecutivo(@Valid @RequestBody FijarConsecutivoRequest request) {
        try {
            return ResponseEntity.ok(empresaService.fijarConsecutivo(request));
        } catch (ConsecutivoInvalidoException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    /** Mismo patrón que {@code AuthController#usuarioIdAutenticado} -- ver su javadoc. */
    private Optional<UUID> usuarioIdAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || !(autenticacion.getPrincipal() instanceof UUID usuarioId)) {
            return Optional.empty();
        }
        return Optional.of(usuarioId);
    }
}
