/**
 * Categoría A portada del ERP de referencia: interfaz de consulta CABYS y de contribuyente
 * (Fase 6), cliente OAuth2 + recepción de comprobantes de Hacienda (Fase 8), generador de XML
 * v4.4 y firma digital XML-DSig/XAdES-BES (reservados para una fase futura).
 *
 * <p>Fase 6 solo consume {@link cr.ac.fractall.hacienda.servicio.HaciendaApiService#buscarCabys}
 * (sección 4.10 de {@code arquitectura-facturacion-electronica-cr.md}). Fase 8 agrega
 * {@link cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService} (autenticación OAuth2 +
 * envío/consulta de comprobantes) como cliente standalone, todavía sin consumidor propio -- no se
 * conecta a {@code FacturaService} ni a ningún controlador en esta fase; ver su javadoc sobre por
 * qué NO se llama {@code HaciendaApiService} pese a que así se llama en el ERP de referencia. El
 * generador de XML v4.4 y la firma digital se portan como unidad atómica para una fase futura --
 * ver sección 8 y 10 del documento de arquitectura, y la memoria de proyecto sobre el ERP de
 * referencia para ubicar el código origen.
 */
package cr.ac.fractall.hacienda.servicio;
