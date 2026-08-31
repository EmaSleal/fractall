package cr.ac.fractall.reportes.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import cr.ac.fractall.reportes.dto.CarteraPendiente;
import cr.ac.fractall.reportes.dto.ComparativoPeriodoAnterior;
import cr.ac.fractall.reportes.dto.FilaCobrosPorMedioPago;
import cr.ac.fractall.reportes.dto.FilaDetalleCobro;
import cr.ac.fractall.reportes.dto.FilaDetalleVenta;
import cr.ac.fractall.reportes.dto.FilaVentasPorCondicion;
import cr.ac.fractall.reportes.dto.ReporteFlujoCajaResponse;
import cr.ac.fractall.reportes.dto.SerieCobros;
import cr.ac.fractall.reportes.dto.SerieVentas;

/**
 * Export a PDF de {@link ReporteFlujoCajaResponse} (Release 3 / Fase D, Change 2 de 2, PR7, ver el
 * diseño obs #918). Página 1 = los mismos cuatro bloques de {@code ReporteFlujoCajaExcelWriter}
 * (VENTAS, COBROS, CARTERA PENDIENTE, COMPARATIVO PERÍODO ANTERIOR); página 2+ = sección
 * DetalleVentas con su encabezado de columnas registrado como encabezado repetible ({@link
 * CursorPdf#registrarEncabezadoRepetible}); página siguiente+ = sección DetalleCobros, con su
 * PROPIO encabezado.
 *
 * <p>{@code CursorPdf} solo tiene UN slot de encabezado repetible (decisión de diseño B10):
 * re-registrarlo antes de DetalleCobros REEMPLAZA al de DetalleVentas -- exactamente el
 * comportamiento que esta clase necesita, y logrado con CERO cambios a {@code CursorPdf} (ver
 * tarea 7.7, verificación de no-regresión explícita del cambio). {@code nuevaPagina()} se invoca
 * explícitamente entre secciones (Resumen→DetalleVentas, DetalleVentas→DetalleCobros), igual que
 * {@code ReporteIvaPdfWriter} entre Resumen y Detalle.
 *
 * <p>Deliberadamente sin columna {@code medioPago} en DetalleVentas -- mismo criterio D.1/D.6 que
 * {@code ReporteFlujoCajaExcelWriter}.
 */
public final class ReporteFlujoCajaPdfWriter {

    private static final float MARGEN_IZQ = 50f;
    private static final float MARGEN_SUP = 50f;
    private static final float MARGEN_INF = 50f;
    private static final float INTERLINEA = 14f;

    private static final int FUENTE_TITULO = 14;
    private static final int FUENTE_NORMAL = 10;
    private static final int FUENTE_PEQUENA = 8;

    private static final String ENCABEZADO_DETALLE_VENTAS = String.format(
            "%-11s %-4s %-22s %-7s %14s %5s", "Fecha", "Tipo", "Consecutivo", "CondVta", "Total", "Signo");

    private static final String ENCABEZADO_DETALLE_COBROS = String.format(
            "%-11s %-22s %-7s %-10s %14s", "Fecha", "Consecutivo", "CondVta", "MedioPago", "Monto");

    private ReporteFlujoCajaPdfWriter() {
    }

    public static byte[] generar(ReporteFlujoCajaResponse reporte) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fuenteBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fuenteNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (CursorPdf cursor = new CursorPdf(
                    doc, PDRectangle.A4, MARGEN_IZQ, MARGEN_SUP, MARGEN_INF, INTERLINEA)) {

                escribirResumen(cursor, reporte, fuenteBold, fuenteNormal);

                cursor.nuevaPagina();
                cursor.registrarEncabezadoRepetible(
                        () -> cursor.escribir(ENCABEZADO_DETALLE_VENTAS, fuenteBold, FUENTE_PEQUENA));
                cursor.escribir(ENCABEZADO_DETALLE_VENTAS, fuenteBold, FUENTE_PEQUENA);
                escribirDetalleVentas(cursor, reporte, fuenteNormal);

                cursor.nuevaPagina();
                cursor.registrarEncabezadoRepetible(
                        () -> cursor.escribir(ENCABEZADO_DETALLE_COBROS, fuenteBold, FUENTE_PEQUENA));
                cursor.escribir(ENCABEZADO_DETALLE_COBROS, fuenteBold, FUENTE_PEQUENA);
                escribirDetalleCobros(cursor, reporte, fuenteNormal);
            }

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            doc.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del reporte de flujo de caja", e);
        }
    }

    private static void escribirResumen(
            CursorPdf cursor,
            ReporteFlujoCajaResponse reporte,
            PDType1Font fuenteBold,
            PDType1Font fuenteNormal) throws IOException {

        cursor.escribir(
                "Reporte de Flujo de Caja " + reporte.desde() + " - " + reporte.hasta(),
                fuenteBold, FUENTE_TITULO);
        cursor.espacio(6f);

        escribirBloqueVentas(cursor, reporte.ventas(), fuenteBold, fuenteNormal);
        cursor.espacio(6f);
        escribirBloqueCobros(cursor, reporte.cobros(), fuenteBold, fuenteNormal);
        cursor.espacio(6f);
        escribirBloqueCartera(cursor, reporte.cartera(), fuenteBold, fuenteNormal);
        cursor.espacio(6f);
        escribirBloqueComparativo(cursor, reporte.comparativo(), fuenteBold, fuenteNormal);
    }

    private static void escribirBloqueVentas(
            CursorPdf cursor, SerieVentas ventas, PDType1Font fuenteBold, PDType1Font fuenteNormal)
            throws IOException {
        cursor.escribir("VENTAS", fuenteBold, FUENTE_NORMAL);
        for (FilaVentasPorCondicion fila : ventas.porCondicionVenta()) {
            cursor.escribir(String.format("%-15s %10d %14s",
                    fila.condicionVenta(), fila.cantidadComprobantes(), fila.total().toPlainString()),
                    fuenteNormal, FUENTE_NORMAL);
        }
        cursor.escribir(String.format("Total Ventas: %s (%d comprobantes)",
                ventas.total().toPlainString(), ventas.cantidadComprobantes()), fuenteBold, FUENTE_NORMAL);
    }

    private static void escribirBloqueCobros(
            CursorPdf cursor, SerieCobros cobros, PDType1Font fuenteBold, PDType1Font fuenteNormal)
            throws IOException {
        cursor.escribir("COBROS", fuenteBold, FUENTE_NORMAL);
        for (FilaCobrosPorMedioPago fila : cobros.porMedioPago()) {
            cursor.escribir(String.format("%-6s %-20s %10d %14s",
                    fila.medioPago(), fila.descripcionMedioPago(), fila.cantidadCobros(),
                    fila.total().toPlainString()), fuenteNormal, FUENTE_NORMAL);
        }
        cursor.escribir(String.format("Total Cobros: %s (%d cobros)",
                cobros.total().toPlainString(), cobros.cantidadCobros()), fuenteBold, FUENTE_NORMAL);
    }

    private static void escribirBloqueCartera(
            CursorPdf cursor, CarteraPendiente cartera, PDType1Font fuenteBold, PDType1Font fuenteNormal)
            throws IOException {
        cursor.escribir("CARTERA PENDIENTE", fuenteBold, FUENTE_NORMAL);
        cursor.escribir(String.format("Corte: %s  Total: %s  Facturas: %d",
                cartera.fechaCorte(), cartera.total().toPlainString(), cartera.cantidadFacturas()),
                fuenteNormal, FUENTE_NORMAL);
    }

    private static void escribirBloqueComparativo(
            CursorPdf cursor, ComparativoPeriodoAnterior comparativo, PDType1Font fuenteBold,
            PDType1Font fuenteNormal) throws IOException {
        cursor.escribir("COMPARATIVO PERIODO ANTERIOR", fuenteBold, FUENTE_NORMAL);
        cursor.escribir(String.format("%s - %s  Ventas: %s  Cobros: %s",
                comparativo.desdeAnterior(), comparativo.hastaAnterior(),
                comparativo.ventasAnterior().toPlainString(), comparativo.cobrosAnterior().toPlainString()),
                fuenteNormal, FUENTE_NORMAL);
        cursor.escribir(String.format("Variacion Ventas: %s  Variacion Cobros: %s",
                comparativo.variacionVentas().toPlainString(), comparativo.variacionCobros().toPlainString()),
                fuenteNormal, FUENTE_NORMAL);
    }

    private static void escribirDetalleVentas(
            CursorPdf cursor, ReporteFlujoCajaResponse reporte, PDType1Font fuenteNormal) throws IOException {
        for (FilaDetalleVenta fila : reporte.detalleVentas()) {
            cursor.escribir(String.format("%-11s %-4s %-22s %-7s %14s %5d",
                    fila.fechaEmision(), fila.tipoComprobante(), fila.consecutivo(), fila.condicionVenta(),
                    fila.total().toPlainString(), fila.signo()), fuenteNormal, FUENTE_PEQUENA);
        }
    }

    private static void escribirDetalleCobros(
            CursorPdf cursor, ReporteFlujoCajaResponse reporte, PDType1Font fuenteNormal) throws IOException {
        for (FilaDetalleCobro fila : reporte.detalleCobros()) {
            cursor.escribir(String.format("%-11s %-22s %-7s %-10s %14s",
                    fila.fechaCobro(), fila.consecutivoFactura(), fila.condicionVenta(), fila.medioPago(),
                    fila.montoCobrado().toPlainString()), fuenteNormal, FUENTE_PEQUENA);
        }
    }
}
