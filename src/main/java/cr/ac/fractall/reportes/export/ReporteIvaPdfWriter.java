package cr.ac.fractall.reportes.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import cr.ac.fractall.reportes.dto.FilaDetalleIva;
import cr.ac.fractall.reportes.dto.FilaResumenIva;
import cr.ac.fractall.reportes.dto.ReporteIvaResponse;

/**
 * Export a PDF de {@link ReporteIvaResponse} (Release 3 / Fase D, PR7, ver el diseño). Página 1 =
 * tabla Resumen + total de débito fiscal; salto explícito a página 2 vía {@link
 * CursorPdf#nuevaPagina()}; página 2 en adelante = filas de Detalle, con su encabezado de columnas
 * registrado como encabezado repetible ({@link CursorPdf#registrarEncabezadoRepetible}) para que se
 * reemita en cada salto de página automático dentro de la sección Detalle.
 *
 * <p>Usa {@link CursorPdf} (PR6) en vez de duplicar la lógica de salto de página de {@code
 * FacturaPdfService} -- ver decisión de diseño A6. Tamaño de página A4 (a diferencia de {@code
 * FacturaPdfService}, que usa {@code PDRectangle.LETTER} para la factura fiscal congelada); mismos
 * idiomas de fuente PDFBox 3.x ({@code Standard14Fonts}) y márgenes/interlínea equivalentes a los
 * de {@code FacturaPdfService}.
 *
 * <p>Deliberadamente sin campo {@code medioPago} en ninguna sección -- ver spec, requisito "No
 * `medio_pago` Field".
 */
public final class ReporteIvaPdfWriter {

    private static final float MARGEN_IZQ = 50f;
    private static final float MARGEN_SUP = 50f;
    private static final float MARGEN_INF = 50f;
    private static final float INTERLINEA = 14f;

    private static final int FUENTE_TITULO = 14;
    private static final int FUENTE_NORMAL = 10;
    private static final int FUENTE_PEQUENA = 8;

    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String ENCABEZADO_RESUMEN =
            String.format("%-8s %8s %14s %14s %14s %14s",
                    "Gravado", "%Imp", "Base Imp.", "Imp.Bruto", "Exon.", "Imp.Neto");

    private static final String ENCABEZADO_DETALLE =
            String.format("%-11s %-4s %-22s %-7s %6s %12s %12s %12s %12s %5s",
                    "Fecha", "Tipo", "Consecutivo", "Gravado", "%Imp", "Subtotal", "Imp.Bruto",
                    "Exon.", "Imp.Neto", "Signo");

    private ReporteIvaPdfWriter() {
    }

    public static byte[] generar(ReporteIvaResponse reporte) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fuenteBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fuenteNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (CursorPdf cursor = new CursorPdf(
                    doc, PDRectangle.A4, MARGEN_IZQ, MARGEN_SUP, MARGEN_INF, INTERLINEA)) {

                escribirResumen(cursor, reporte, fuenteBold, fuenteNormal);

                cursor.nuevaPagina();
                cursor.registrarEncabezadoRepetible(
                        () -> cursor.escribir(ENCABEZADO_DETALLE, fuenteBold, FUENTE_PEQUENA));
                cursor.escribir(ENCABEZADO_DETALLE, fuenteBold, FUENTE_PEQUENA);
                escribirDetalle(cursor, reporte, fuenteNormal);
            }

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            doc.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del reporte de IVA", e);
        }
    }

    private static void escribirResumen(
            CursorPdf cursor,
            ReporteIvaResponse reporte,
            PDType1Font fuenteBold,
            PDType1Font fuenteNormal) throws IOException {

        cursor.escribir(
                "Reporte de IVA " + reporte.desde() + " - " + reporte.hasta(), fuenteBold, FUENTE_TITULO);
        cursor.espacio(6f);

        cursor.escribir(ENCABEZADO_RESUMEN, fuenteBold, FUENTE_NORMAL);
        cursor.lineaHorizontal();
        cursor.espacio(4f);

        for (FilaResumenIva fila : reporte.resumen()) {
            cursor.escribir(String.format("%-8s %8s %14s %14s %14s %14s",
                    fila.gravado() ? "Si" : "No",
                    fila.porcentajeImpuesto().toPlainString(),
                    fila.baseImponible().toPlainString(),
                    fila.impuestoBruto().toPlainString(),
                    fila.exoneraciones().toPlainString(),
                    fila.impuestoNeto().toPlainString()), fuenteNormal, FUENTE_NORMAL);
        }

        cursor.espacio(6f);
        cursor.escribir(
                "Total Debito Fiscal: " + reporte.totalDebitoFiscal().toPlainString(),
                fuenteBold,
                FUENTE_NORMAL);
    }

    private static void escribirDetalle(
            CursorPdf cursor, ReporteIvaResponse reporte, PDType1Font fuenteNormal) throws IOException {
        for (FilaDetalleIva fila : reporte.detalle()) {
            cursor.escribir(String.format("%-11s %-4s %-22s %-7s %6s %12s %12s %12s %12s %5d",
                    fila.fechaEmision().format(FECHA_FORMATTER),
                    fila.tipoComprobante(),
                    fila.consecutivo(),
                    fila.gravado() ? "Si" : "No",
                    fila.porcentajeImpuesto().toPlainString(),
                    fila.subtotal().toPlainString(),
                    fila.impuestoBruto().toPlainString(),
                    fila.montoExoneracion().toPlainString(),
                    fila.impuestoNeto().toPlainString(),
                    fila.signo()), fuenteNormal, FUENTE_PEQUENA);
        }
    }
}
