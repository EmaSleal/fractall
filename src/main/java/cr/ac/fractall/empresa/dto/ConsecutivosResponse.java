package cr.ac.fractall.empresa.dto;

public record ConsecutivosResponse(
        ConsecutivosAmbienteResponse sandbox,
        ConsecutivosAmbienteResponse produccion) {
}
