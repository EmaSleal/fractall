package cr.ac.fractall.empresa.dto;

public record ConsecutivosAmbienteResponse(
        long facturaElectronica,
        long notaDebito,
        long notaCredito,
        long tiquete) {
}
