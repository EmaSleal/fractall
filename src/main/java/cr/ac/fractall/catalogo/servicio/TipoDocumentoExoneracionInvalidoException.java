package cr.ac.fractall.catalogo.servicio;

/**
 * {@code tipoDocumento} no es uno de los 12 códigos del catálogo oficial (sección 4.15.1 de
 * {@code arquitectura-facturacion-electronica-cr.md}), o es {@code '99'} sin
 * {@code nombreInstitucionOtros} -- rechazo explícito ANTES de {@code saveAndFlush}, aunque
 * ambas reglas también las aplique el {@code CHECK} de motor de
 * {@code V8__cliente_exoneracion.sql}.
 */
public class TipoDocumentoExoneracionInvalidoException extends RuntimeException {

    public TipoDocumentoExoneracionInvalidoException(String mensaje) {
        super(mensaje);
    }
}
