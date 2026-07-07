package cr.ac.fractall.catalogo.dto;

import java.util.UUID;

import cr.ac.fractall.catalogo.modelo.Cliente;

public record ClienteResponse(
        UUID id,
        String nombre,
        String tipoIdentificacion,
        String numeroIdentificacion,
        String codigoActividad,
        String codigoProvincia,
        String canton,
        String distrito,
        String otrasSenas,
        String telefono,
        String email,
        boolean requiereFacturaElectronica) {

    public static ClienteResponse desde(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNombre(),
                cliente.getTipoIdentificacion(),
                cliente.getNumeroIdentificacion(),
                cliente.getCodigoActividad(),
                cliente.getCodigoProvincia(),
                cliente.getCanton(),
                cliente.getDistrito(),
                cliente.getOtrasSenas(),
                cliente.getTelefono(),
                cliente.getEmail(),
                cliente.isRequiereFacturaElectronica());
    }
}
