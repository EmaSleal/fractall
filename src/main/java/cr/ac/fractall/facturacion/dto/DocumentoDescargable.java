package cr.ac.fractall.facturacion.dto;

public record DocumentoDescargable<T>(T contenido, String claveNumerica) {}
