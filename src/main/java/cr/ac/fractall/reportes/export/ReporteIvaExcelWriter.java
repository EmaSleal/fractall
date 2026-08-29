package cr.ac.fractall.reportes.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import cr.ac.fractall.reportes.dto.FilaDetalleIva;
import cr.ac.fractall.reportes.dto.FilaResumenIva;
import cr.ac.fractall.reportes.dto.ReporteIvaResponse;

/**
 * Export a XLSX de {@link ReporteIvaResponse} (Release 3 / Fase D, PR5, ver el diseño, decisiones
 * A5/A8). {@code XSSFWorkbook} en memoria (A8 -- el volumen mensual por tenant cabe en memoria, sin
 * el ciclo de vida de archivos temporales de {@code SXSSFWorkbook}); dos hojas, {@code Resumen}
 * luego {@code Detalle}, en ese orden exacto.
 *
 * <p>Todos los montos se escriben con {@link Cell#setCellValue(double)}, nunca como texto (A5 --
 * XLSX almacena numeros como IEEE-754 por especificacion; POI no ofrece una celda
 * {@link BigDecimal} nativa, y escribir montos como String rompería la suma nativa de Excel en la
 * hoja Detalle, que es el punto entero de esa hoja). El JSON de {@code GET /reportes/iva} sigue
 * siendo el artefacto fiscal autoritativo.
 *
 * <p>Un unico {@link CellStyle} compartido con formato {@code #,##0.00000} (misma escala que
 * {@code CalculadoraImpuestoLinea#ESCALA_MONETARIA}) se aplica a toda columna de MONTO -- no a
 * porcentaje, numero de linea ni signo, que son valores enteros/porcentuales, no montos fiscales.
 *
 * <p>Deliberadamente sin campo {@code medioPago} en ninguna hoja -- ver spec, requisito
 * "No `medio_pago` Field".
 */
public final class ReporteIvaExcelWriter {

    private static final String[] ENCABEZADO_RESUMEN = {
        "Gravado", "% Impuesto", "Base Imponible", "Impuesto Bruto", "Exoneraciones", "Impuesto Neto"
    };

    private static final String[] ENCABEZADO_DETALLE = {
        "Fecha Emisión", "Tipo Comprobante", "Consecutivo", "Clave Numérica", "Factura Id",
        "Cliente Id", "Factura Referencia Id", "Número Línea", "Gravado", "% Impuesto", "Subtotal",
        "Impuesto Bruto", "Monto Exoneración", "Impuesto Neto", "Signo"
    };

    private ReporteIvaExcelWriter() {
    }

    public static byte[] generar(ReporteIvaResponse reporte) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle estiloMonto = crearEstiloMonto(workbook);
            escribirResumen(workbook.createSheet("Resumen"), estiloMonto, reporte);
            escribirDetalle(workbook.createSheet("Detalle"), estiloMonto, reporte);

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            workbook.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el workbook del reporte de IVA", e);
        }
    }

    private static CellStyle crearEstiloMonto(XSSFWorkbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00000"));
        return estilo;
    }

    private static void escribirResumen(Sheet hoja, CellStyle estiloMonto, ReporteIvaResponse reporte) {
        escribirEncabezado(hoja.createRow(0), ENCABEZADO_RESUMEN);

        int numeroFila = 1;
        for (FilaResumenIva fila : reporte.resumen()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.gravado());
            escribirMonto(filaHoja, 1, fila.porcentajeImpuesto(), null);
            escribirMonto(filaHoja, 2, fila.baseImponible(), estiloMonto);
            escribirMonto(filaHoja, 3, fila.impuestoBruto(), estiloMonto);
            escribirMonto(filaHoja, 4, fila.exoneraciones(), estiloMonto);
            escribirMonto(filaHoja, 5, fila.impuestoNeto(), estiloMonto);
        }

        Row filaTotal = hoja.createRow(numeroFila);
        filaTotal.createCell(0).setCellValue("Total Débito Fiscal");
        escribirMonto(filaTotal, 5, reporte.totalDebitoFiscal(), estiloMonto);
    }

    private static void escribirDetalle(Sheet hoja, CellStyle estiloMonto, ReporteIvaResponse reporte) {
        escribirEncabezado(hoja.createRow(0), ENCABEZADO_DETALLE);

        int numeroFila = 1;
        for (FilaDetalleIva fila : reporte.detalle()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.fechaEmision().toString());
            filaHoja.createCell(1).setCellValue(fila.tipoComprobante());
            filaHoja.createCell(2).setCellValue(fila.consecutivo());
            filaHoja.createCell(3).setCellValue(fila.claveNumerica());
            filaHoja.createCell(4).setCellValue(fila.facturaId().toString());
            filaHoja.createCell(5).setCellValue(fila.clienteId().toString());
            filaHoja.createCell(6).setCellValue(textoUuid(fila.facturaReferenciaId()));
            filaHoja.createCell(7).setCellValue(fila.numeroLinea());
            filaHoja.createCell(8).setCellValue(fila.gravado());
            escribirMonto(filaHoja, 9, fila.porcentajeImpuesto(), null);
            escribirMonto(filaHoja, 10, fila.subtotal(), estiloMonto);
            escribirMonto(filaHoja, 11, fila.impuestoBruto(), estiloMonto);
            escribirMonto(filaHoja, 12, fila.montoExoneracion(), estiloMonto);
            escribirMonto(filaHoja, 13, fila.impuestoNeto(), estiloMonto);
            filaHoja.createCell(14).setCellValue(fila.signo());
        }
    }

    private static void escribirEncabezado(Row fila, String[] encabezado) {
        for (int i = 0; i < encabezado.length; i++) {
            fila.createCell(i).setCellValue(encabezado[i]);
        }
    }

    private static void escribirMonto(Row fila, int columna, BigDecimal monto, CellStyle estilo) {
        Cell celda = fila.createCell(columna);
        celda.setCellValue(monto.doubleValue());
        if (estilo != null) {
            celda.setCellStyle(estilo);
        }
    }

    private static String textoUuid(UUID id) {
        return id == null ? "" : id.toString();
    }
}
