package cr.ac.fractall.empresa.controlador;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.CargarCertificadoRequest;
import cr.ac.fractall.empresa.dto.ConfigurarCredencialHaciendaRequest;
import cr.ac.fractall.empresa.dto.EmpresaResponse;
import cr.ac.fractall.empresa.servicio.CertificadoInvalidoException;
import cr.ac.fractall.empresa.servicio.EmpresaService;
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
@RestController
@RequestMapping("/empresa")
public class EmpresaController {

    private static final MensajeResponse MENSAJE_SIN_AUTENTICAR =
            new MensajeResponse("No autenticado.");

    private final EmpresaService empresaService;

    public EmpresaController(EmpresaService empresaService) {
        this.empresaService = empresaService;
    }

    @PatchMapping
    public ResponseEntity<EmpresaResponse> actualizarDatosFiscales(
            @Valid @RequestBody ActualizarDatosFiscalesRequest request) {
        return ResponseEntity.ok(empresaService.actualizarDatosFiscales(request));
    }

    @PostMapping(value = "/certificado", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> cargarCertificado(@Valid @ModelAttribute CargarCertificadoRequest request) {
        if (request.certificado().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MensajeResponse("El archivo .p12 es obligatorio"));
        }
        try {
            byte[] certificadoP12 = request.certificado().getBytes();
            return ResponseEntity.ok(empresaService.cargarCertificado(certificadoP12, request.pin()));
        } catch (IOException excepcion) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MensajeResponse("No se pudo leer el archivo .p12 enviado."));
        } catch (CertificadoInvalidoException excepcion) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MensajeResponse(excepcion.getMessage()));
        }
    }

    @PostMapping("/credenciales-hacienda")
    public ResponseEntity<?> configurarCredencialHacienda(
            @Valid @RequestBody ConfigurarCredencialHaciendaRequest request) {
        Optional<UUID> usuarioId = usuarioIdAutenticado();
        if (usuarioId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(MENSAJE_SIN_AUTENTICAR);
        }

        EmpresaResponse respuesta = empresaService.configurarCredencialHacienda(
                request.usuarioHacienda(), request.password(), usuarioId.get());
        return ResponseEntity.ok(respuesta);
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
