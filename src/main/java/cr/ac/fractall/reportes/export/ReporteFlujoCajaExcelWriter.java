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
 * Export a XLSX de {@link ReporteFlujoCajaResponse} (Release 3 / Fase D, Change 2 de 2, PR6, ver
 * el diseño obs #918). Reusa la convención Resumen/Detalle establecida por
 * {@code ReporteIvaExcelWriter} SIN modificarla (mismo {@code XSSFWorkbook} en memoria, mismo
 * {@code CellStyle} compartido de monto {@code #,##0.00000}) -- generalizada de "1 Resumen + 1
 * Detalle" a "1 Resumen + N Detalle" (spec, requisito "Excel Sheet Cardinality Generalizes to 1
 * Resumen + N Detalle"): tres hojas en orden exacto {@code Resumen}, {@code DetalleVentas},
 * {@code DetalleCobros}.
 *
 * <p>{@code Resumen} lleva cuatro bloques separados por una fila de título ({@code VENTAS},
 * {@code COBROS}, {@code CARTERA PENDIENTE}, {@code COMPARATIVO PERÍODO ANTERIOR}), cada uno
 * seguido de su propio encabezado de columnas -- ver el diseño, sección "Interfaces / Contracts".
 *
 * <p>{@code DetalleVentas} deliberadamente SIN columna {@code medioPago} -- esa columna pertenece
 * únicamente a {@code DetalleCobros} (D.1/D.6: {@code medio_pago} viene de {@code cobro_factura},
 * nunca de {@code factura}, y una venta no tiene medio de pago propio). Todos los montos vía
 * {@link Cell#setCellValue(double)}, nunca como texto (misma razón que {@code ReporteIvaExcelWriter}:
 * XLSX almacena números como IEEE-754 por especificación, y escribirlos como String rompería la
 * suma nativa de Excel en cada hoja de detalle).
 */
public final class ReporteFlujoCajaExcelWriter {

    private static final String[] ENCABEZADO_VENTAS = {"Condición Venta", "Cantidad Comprobantes", "Total"};
    private static final String[] ENCABEZADO_COBROS =
            {"Medio Pago", "Descripción", "Cantidad Cobros", "Total"};
    private static final String[] ENCABEZADO_CARTERA = {"Fecha Corte", "Total", "Cantidad Facturas"};
    private static final String[] ENCABEZADO_COMPARATIVO = {
        "Desde Anterior", "Hasta Anterior", "Ventas Anterior", "Cobros Anterior",
        "Variación Ventas", "Variación Cobros"
    };
    private static final String[] ENCABEZADO_DETALLE_VENTAS = {
        "Fecha Emisión", "Tipo Comprobante", "Consecutivo", "Condición Venta", "Factura Id",
        "Cliente Id", "Factura Referencia Id", "Moneda", "Total", "Signo"
    };
    private static final String[] ENCABEZADO_DETALLE_COBROS = {
        "Fecha Cobro", "Cobro Id", "Factura Id", "Consecutivo Factura", "Condición Venta",
        "Medio Pago", "Descripción Medio Pago", "Referencia", "Monto Cobrado"
    };

    private ReporteFlujoCajaExcelWriter() {
    }

    public static byte[] generar(ReporteFlujoCajaResponse reporte) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle estiloMonto = crearEstiloMonto(workbook);
            escribirResumen(workbook.createSheet("Resumen"), estiloMonto, reporte);
            escribirDetalleVentas(workbook.createSheet("DetalleVentas"), estiloMonto, reporte);
            escribirDetalleCobros(workbook.createSheet("DetalleCobros"), estiloMonto, reporte);

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            workbook.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo generar el workbook del reporte de flujo de caja", e);
        }
    }

    private static CellStyle crearEstiloMonto(XSSFWorkbook workbook) {
        CellStyle estilo = workbook.createCellStyle();
        estilo.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00000"));
        return estilo;
    }

    private static void escribirResumen(Sheet hoja, CellStyle estiloMonto, ReporteFlujoCajaResponse reporte) {
        int numeroFila = escribirBloqueVentas(hoja, estiloMonto, 0, reporte.ventas());
        numeroFila = escribirBloqueCobros(hoja, estiloMonto, numeroFila + 1, reporte.cobros());
        numeroFila = escribirBloqueCartera(hoja, estiloMonto, numeroFila + 1, reporte.cartera());
        escribirBloqueComparativo(hoja, estiloMonto, numeroFila + 1, reporte.comparativo());
    }

    private static int escribirBloqueVentas(
            Sheet hoja, CellStyle estiloMonto, int filaInicio, SerieVentas ventas) {
        int numeroFila = filaInicio;
        hoja.createRow(numeroFila++).createCell(0).setCellValue("VENTAS");
        escribirEncabezado(hoja.createRow(numeroFila++), ENCABEZADO_VENTAS);
        for (FilaVentasPorCondicion fila : ventas.porCondicionVenta()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.condicionVenta());
            filaHoja.createCell(1).setCellValue(fila.cantidadComprobantes());
            escribirMonto(filaHoja, 2, fila.total(), estiloMonto);
        }
        Row filaTotal = hoja.createRow(numeroFila++);
        filaTotal.createCell(0).setCellValue("Total Ventas");
        filaTotal.createCell(1).setCellValue(ventas.cantidadComprobantes());
        escribirMonto(filaTotal, 2, ventas.total(), estiloMonto);
        return numeroFila;
    }

    private static int escribirBloqueCobros(
            Sheet hoja, CellStyle estiloMonto, int filaInicio, SerieCobros cobros) {
        int numeroFila = filaInicio;
        hoja.createRow(numeroFila++).createCell(0).setCellValue("COBROS");
        escribirEncabezado(hoja.createRow(numeroFila++), ENCABEZADO_COBROS);
        for (FilaCobrosPorMedioPago fila : cobros.porMedioPago()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.medioPago());
            filaHoja.createCell(1).setCellValue(fila.descripcionMedioPago());
            filaHoja.createCell(2).setCellValue(fila.cantidadCobros());
            escribirMonto(filaHoja, 3, fila.total(), estiloMonto);
        }
        Row filaTotal = hoja.createRow(numeroFila++);
        filaTotal.createCell(0).setCellValue("Total Cobros");
        filaTotal.createCell(2).setCellValue(cobros.cantidadCobros());
        escribirMonto(filaTotal, 3, cobros.total(), estiloMonto);
        return numeroFila;
    }

    private static int escribirBloqueCartera(
            Sheet hoja, CellStyle estiloMonto, int filaInicio, CarteraPendiente cartera) {
        int numeroFila = filaInicio;
        hoja.createRow(numeroFila++).createCell(0).setCellValue("CARTERA PENDIENTE");
        escribirEncabezado(hoja.createRow(numeroFila++), ENCABEZADO_CARTERA);
        Row filaDatos = hoja.createRow(numeroFila++);
        filaDatos.createCell(0).setCellValue(cartera.fechaCorte().toString());
        escribirMonto(filaDatos, 1, cartera.total(), estiloMonto);
        filaDatos.createCell(2).setCellValue(cartera.cantidadFacturas());
        return numeroFila;
    }

    private static void escribirBloqueComparativo(
            Sheet hoja, CellStyle estiloMonto, int filaInicio, ComparativoPeriodoAnterior comparativo) {
        int numeroFila = filaInicio;
        hoja.createRow(numeroFila++).createCell(0).setCellValue("COMPARATIVO PERÍODO ANTERIOR");
        escribirEncabezado(hoja.createRow(numeroFila++), ENCABEZADO_COMPARATIVO);
        Row filaDatos = hoja.createRow(numeroFila);
        filaDatos.createCell(0).setCellValue(comparativo.desdeAnterior().toString());
        filaDatos.createCell(1).setCellValue(comparativo.hastaAnterior().toString());
        escribirMonto(filaDatos, 2, comparativo.ventasAnterior(), estiloMonto);
        escribirMonto(filaDatos, 3, comparativo.cobrosAnterior(), estiloMonto);
        escribirMonto(filaDatos, 4, comparativo.variacionVentas(), estiloMonto);
        escribirMonto(filaDatos, 5, comparativo.variacionCobros(), estiloMonto);
    }

    private static void escribirDetalleVentas(
            Sheet hoja, CellStyle estiloMonto, ReporteFlujoCajaResponse reporte) {
        escribirEncabezado(hoja.createRow(0), ENCABEZADO_DETALLE_VENTAS);
        int numeroFila = 1;
        for (FilaDetalleVenta fila : reporte.detalleVentas()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.fechaEmision().toString());
            filaHoja.createCell(1).setCellValue(fila.tipoComprobante());
            filaHoja.createCell(2).setCellValue(fila.consecutivo());
            filaHoja.createCell(3).setCellValue(fila.condicionVenta());
            filaHoja.createCell(4).setCellValue(fila.facturaId().toString());
            filaHoja.createCell(5).setCellValue(fila.clienteId().toString());
            filaHoja.createCell(6).setCellValue(textoUuid(fila.facturaReferenciaId()));
            filaHoja.createCell(7).setCellValue(fila.moneda());
            escribirMonto(filaHoja, 8, fila.total(), estiloMonto);
            filaHoja.createCell(9).setCellValue(fila.signo());
        }
    }

    private static void escribirDetalleCobros(
            Sheet hoja, CellStyle estiloMonto, ReporteFlujoCajaResponse reporte) {
        escribirEncabezado(hoja.createRow(0), ENCABEZADO_DETALLE_COBROS);
        int numeroFila = 1;
        for (FilaDetalleCobro fila : reporte.detalleCobros()) {
            Row filaHoja = hoja.createRow(numeroFila++);
            filaHoja.createCell(0).setCellValue(fila.fechaCobro().toString());
            filaHoja.createCell(1).setCellValue(fila.cobroId().toString());
            filaHoja.createCell(2).setCellValue(fila.facturaId().toString());
            filaHoja.createCell(3).setCellValue(fila.consecutivoFactura());
            filaHoja.createCell(4).setCellValue(fila.condicionVenta());
            filaHoja.createCell(5).setCellValue(fila.medioPago());
            filaHoja.createCell(6).setCellValue(fila.descripcionMedioPago());
            filaHoja.createCell(7).setCellValue(fila.referencia());
            escribirMonto(filaHoja, 8, fila.montoCobrado(), estiloMonto);
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
