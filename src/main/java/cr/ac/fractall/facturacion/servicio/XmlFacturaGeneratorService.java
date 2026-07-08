package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * Generador del XML de Factura Electrónica v4.4 de Hacienda Costa Rica (Fase 8, sección 4.10/8
 * de {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Portado (Categoría A) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../facturacion/electronica/service/impl/XmlGeneratorServiceImpl.java},
 * SOLO el método {@code generarXmlFactura} y su grafo de llamadas -- {@code generarXmlTiquete}/
 * {@code generarXmlNotaCredito}/{@code generarXmlNotaDebito} quedan deliberadamente fuera de
 * alcance del Release 1 (sección 8.2). La validación contra el XSD real (equivalente a la llamada
 * a {@code XmlValidator.validarXml} del original) está conectada vía
 * {@link XmlFacturaXsdValidator}.
 *
 * <p>Deliberadamente NO se llama {@code XmlGeneratorService} pese a que así se llama en el ERP de
 * referencia -- ese nombre implicaría cubrir las 4 variantes de comprobante que el original genera
 * y que aquí no se portan; {@code XmlFacturaGeneratorService} nombra con precisión lo que esta
 * interfaz realmente hace (mismo principio ya aplicado a
 * {@code cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService}, ver su javadoc).
 *
 * <p>A diferencia del original, que navega relaciones JPA vivas
 * ({@code comprobante.getFactura().getCliente()}, {@code linea.getProducto()}, etc.), nuestras
 * entidades de facturación/catálogo solo guardan claves foráneas UUID planas -- este servicio
 * resuelve explícitamente {@code Factura}/{@code Cliente}/{@code Empresa}/{@code Producto}/
 * {@code ClienteExoneracion} vía sus repositorios a partir del {@code ComprobanteElectronico}
 * indicado.
 */
public interface XmlFacturaGeneratorService {

    /**
     * Genera el XML de Factura Electrónica v4.4 para el comprobante indicado.
     *
     * @param comprobanteId id de un {@code ComprobanteElectronico} del tenant actual
     *     ({@link cr.ac.fractall.tenant.TenantContext})
     * @return el XML generado, como {@code String}, ya validado contra el XSD v4.4 de Hacienda
     *     ({@link XmlFacturaXsdValidator}) pero sin firmar (XAdES-BES es una fase futura separada)
     * @throws ComprobanteElectronicoNoEncontradoException si no existe un comprobante con ese id
     *     para el tenant actual
     * @throws XmlFacturaInvalidoException si el XML generado no cumple el XSD -- bug interno del
     *     generador, ver el javadoc de esa excepción
     */
    String generarXmlFactura(UUID comprobanteId);
}
