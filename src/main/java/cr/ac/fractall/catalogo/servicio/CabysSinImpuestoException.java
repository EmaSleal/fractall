package cr.ac.fractall.catalogo.servicio;

/**
 * El código CABYS SÍ tuvo coincidencia exacta, pero la propia API de Hacienda devolvió el campo
 * {@code impuesto} vacío o nulo -- requisito duro, no negociable (sección 4.10 y 10 de
 * {@code arquitectura-facturacion-electronica-cr.md}): jamás se asume una tarifa por defecto
 * (ej. 13%) en este caso. Nada se persiste cuando se lanza. Distinta de
 * {@link CodigoCabysInvalidoException} a propósito -- el llamador necesita distinguir "código
 * no existe" de "código existe pero Hacienda no publicó su tarifa todavía".
 */
public class CabysSinImpuestoException extends RuntimeException {

    public CabysSinImpuestoException(String codigoCabys) {
        super("El código CABYS '" + codigoCabys
                + "' no trae el campo 'impuesto' en la respuesta de Hacienda -- no se asume una tarifa por defecto");
    }
}
