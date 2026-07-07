package cr.ac.fractall.catalogo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionService;

public record ClienteExoneracionResponse(
        UUID id,
        UUID clienteId,
        String tipoDocumento,
        String numeroDocumento,
        String nombreInstitucion,
        String numeroArticulo,
        String inciso,
        String nombreInstitucionOtros,
        LocalDateTime fechaEmision,
        LocalDateTime fechaVencimiento,
        BigDecimal porcentajeExoneracion,
        boolean activo,
        boolean vigente) {

    public static ClienteExoneracionResponse desde(ClienteExoneracion exoneracion) {
        return new ClienteExoneracionResponse(
                exoneracion.getId(),
                exoneracion.getClienteId(),
                exoneracion.getTipoDocumento(),
                exoneracion.getNumeroDocumento(),
                exoneracion.getNombreInstitucion(),
                exoneracion.getNumeroArticulo(),
                exoneracion.getInciso(),
                exoneracion.getNombreInstitucionOtros(),
                exoneracion.getFechaEmision(),
                exoneracion.getFechaVencimiento(),
                exoneracion.getPorcentajeExoneracion(),
                exoneracion.isActivo(),
                ClienteExoneracionService.estaVigente(exoneracion));
    }
}
