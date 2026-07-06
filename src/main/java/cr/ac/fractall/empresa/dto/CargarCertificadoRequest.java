package cr.ac.fractall.empresa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cuerpo multipart de {@code POST /empresa/certificado}: el archivo {@code .p12} crudo más su
 * PIN. El PIN se valida ANTES de escribir nada (sección 6.4) -- ver
 * {@code EmpresaService#cargarCertificado}.
 */
public record CargarCertificadoRequest(

        @NotNull(message = "El archivo .p12 es obligatorio")
        MultipartFile certificado,

        @NotBlank(message = "El PIN es obligatorio")
        String pin) {
}
