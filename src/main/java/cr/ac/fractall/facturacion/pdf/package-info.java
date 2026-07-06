/**
 * Generación del PDF de factura vía Apache PDFBox ({@code FacturaPdfService}).
 *
 * <p>Resuelve la empresa emisora vía {@code factura.empresa_id}, nunca vía un
 * "principal" implícito — ver bitácora de riesgos, sección 7 de
 * {@code arquitectura-facturacion-electronica-cr.md}.
 */
package cr.ac.fractall.facturacion.pdf;
