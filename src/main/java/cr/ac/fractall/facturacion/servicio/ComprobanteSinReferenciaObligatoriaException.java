package cr.ac.fractall.facturacion.servicio;

import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;

/**
 * {@link XmlFacturaGeneratorService#generarXmlFactura} construyó un comprobante de un tipo cuyo
 * {@link TipoComprobantePerfil#getReferenciasMinimas()} exige al menos una
 * {@code <InformacionReferencia>} (Nota de Crédito/Débito, XSD real: {@code InformacionReferencia}
 * sin {@code minOccurs="0"}, mínimo 1 ocurrencia), pero la {@code Factura} asociada no tiene
 * ninguna fila en {@code factura_informacion_referencia}.
 *
 * <p>SIEMPRE es un bug interno (dato de negocio faltante que debió bloquear antes de llegar acá,
 * no una entrada de usuario inválida en este punto) -- mismo principio que
 * {@link XmlFacturaInvalidoException}: falla ruidosamente en Java con un mensaje diagnosticable
 * en vez de dejar que el XSD reporte una cardinalidad incumplida de forma opaca. Deliberadamente
 * NO se mapea en {@code GlobalExceptionHandler} (queda como 500, mismo tratamiento que
 * {@link XmlFacturaInvalidoException}) -- ver el diseño de Fase B, decisión D-G.
 */
public class ComprobanteSinReferenciaObligatoriaException extends RuntimeException {

    public ComprobanteSinReferenciaObligatoriaException(TipoComprobantePerfil perfil, int referenciasEncontradas) {
        super("El comprobante de tipo " + perfil + " requiere al menos "
                + perfil.getReferenciasMinimas() + " InformacionReferencia, pero tiene "
                + referenciasEncontradas);
    }
}
