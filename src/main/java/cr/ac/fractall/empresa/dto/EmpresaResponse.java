package cr.ac.fractall.empresa.dto;

import java.util.UUID;

import cr.ac.fractall.empresa.modelo.Empresa;

/**
 * Respuesta compartida por los 3 endpoints de {@code EmpresaController} -- incluye
 * {@code status} siempre, para que el llamador observe la máquina de estados de la sección
 * 4.1 progresar tras cada paso, sin tener que consultar un endpoint aparte.
 *
 * <p>{@code certificadoPorAmbiente} y {@code credencialesPorAmbiente} indican, para cada uno
 * de los dos ambientes de Hacienda (SANDBOX/PRODUCCION), si existe un {@code
 * certificado_hacienda} o una {@code credencial_hacienda} respectivamente -- sin importar cuál
 * ambiente esté activo. Solo exponen booleanos -- nunca la ruta del secreto en Vault.
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
        String status,
        EstadoAmbientesResponse certificadoPorAmbiente,
        EstadoAmbientesResponse credencialesPorAmbiente) {

    public static EmpresaResponse desde(
            Empresa empresa,
            EstadoAmbientesResponse certificadoPorAmbiente,
            EstadoAmbientesResponse credencialesPorAmbiente) {
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
                empresa.getStatus(),
                certificadoPorAmbiente,
                credencialesPorAmbiente);
    }
}
