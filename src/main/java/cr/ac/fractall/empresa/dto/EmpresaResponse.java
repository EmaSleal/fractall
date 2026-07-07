package cr.ac.fractall.empresa.dto;

import java.util.UUID;

import cr.ac.fractall.empresa.modelo.Empresa;

/**
 * Respuesta compartida por los 3 endpoints de {@code EmpresaController} -- incluye
 * {@code status} siempre, para que el llamador observe la máquina de estados de la sección
 * 4.1 progresar tras cada paso, sin tener que consultar un endpoint aparte.
 */
public record EmpresaResponse(
        UUID id,
        String razonSocial,
        String nombreComercial,
        String numeroIdentificacion,
        String tipoIdentificacion,
        String codigoActividad,
        String codigoProvincia,
        String canton,
        String distrito,
        String barrio,
        String otrasSenas,
        String telefono,
        String email,
        String ambienteHacienda,
        String status) {

    public static EmpresaResponse desde(Empresa empresa) {
        return new EmpresaResponse(
                empresa.getId(),
                empresa.getRazonSocial(),
                empresa.getNombreComercial(),
                empresa.getNumeroIdentificacion(),
                empresa.getTipoIdentificacion(),
                empresa.getCodigoActividad(),
                empresa.getCodigoProvincia(),
                empresa.getCanton(),
                empresa.getDistrito(),
                empresa.getBarrio(),
                empresa.getOtrasSenas(),
                empresa.getTelefono(),
                empresa.getEmail(),
                empresa.getAmbienteHacienda(),
                empresa.getStatus());
    }
}
