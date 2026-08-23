package cr.ac.fractall.empresa.dto;

/**
 * Estado de un concepto (certificado .p12, credenciales de Hacienda) para los dos ambientes
 * posibles de una empresa, para que el frontend pueda mostrar ambos sin depender de cuál está
 * activo (ver {@link EmpresaResponse}).
 */
public record EstadoAmbientesResponse(boolean sandbox, boolean produccion) {
}
