package cr.ac.fractall.reportes.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.reportes.dto.FilaDetalleIva;
import cr.ac.fractall.reportes.dto.FilaResumenIva;
import cr.ac.fractall.reportes.dto.ReporteIvaResponse;

/**
 * Prueba unitaria de {@link ReporteIvaExcelWriter} (Release 3 / Fase D, PR5, ver el diseño,
 * decisiones A5/A8). Plain JUnit + workbook POI en memoria -- SIN Spring context, SIN
 * Testcontainers, mismo criterio que {@code CalculadoraImpuestoLineaTest}: el writer es una
 * función pura sobre un {@link ReporteIvaResponse} ya construido, la forma real del fetch queda
 * probada por {@code ReporteIvaIT} en PR4.
 */
class ReporteIvaExportTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 8, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 8, 31);

    private FilaResumenIva filaResumen(
            boolean gravado, String porcentaje, String base, String bruto, String exoneraciones, String neto) {
        return new FilaResumenIva(
                gravado, new BigDecimal(porcentaje), new BigDecimal(base), new BigDecimal(bruto),
                new BigDecimal(exoneraciones), new BigDecimal(neto));
    }

    private FilaDetalleIva filaDetalle(String consecutivo, String subtotal, String bruto, String neto, int signo) {
        return new FilaDetalleIva(
                LocalDate.of(2026, 8, 15), "01", consecutivo, "clave-" + consecutivo,
                UUID.randomUUID(), UUID.randomUUID(), null, 1, true,
                new BigDecimal("13.00"), new BigDecimal(subtotal), new BigDecimal(bruto),
                BigDecimal.ZERO, new BigDecimal(neto), signo);
    }

    private XSSFWorkbook generarYLeer(ReporteIvaResponse reporte) throws IOException {
        byte[] bytes = ReporteIvaExcelWriter.generar(reporte);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void excelTieneExactamenteDosHojasResumenYDetalle() throws IOException {
        ReporteIvaResponse reporte = new ReporteIvaResponse(
                DESDE, HASTA,
                List.of(filaResumen(true, "13.00", "1000.00000", "130.00000", "0.00000", "130.00000")),
                List.of(filaDetalle("00100001010000000001", "1000.00000", "130.00000", "130.00000", 1)),
                new BigDecimal("130.00000"));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("Resumen");
            assertThat(workbook.getSheetName(1)).isEqualTo("Detalle");
        }
    }

    @Test
    void hojaResumenCoincideConTotalesAgregados() throws IOException {
        ReporteIvaResponse reporte = new ReporteIvaResponse(
                DESDE, HASTA,
                List.of(
                        filaResumen(true, "13.00", "1000.00000", "130.00000", "0.00000", "130.00000"),
                        filaResumen(false, "0.00", "500.00000", "0.00000", "0.00000", "0.00000")),
                List.of(filaDetalle("00100001010000000001", "1000.00000", "130.00000", "130.00000", 1)),
                new BigDecimal("130.00000"));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet resumen = workbook.getSheet("Resumen");
            Row primeraFila = resumen.getRow(1);
            assertThat(primeraFila.getCell(2).getNumericCellValue()).isCloseTo(1000.00000, within(0.000001));
            assertThat(primeraFila.getCell(3).getNumericCellValue()).isCloseTo(130.00000, within(0.000001));
            assertThat(primeraFila.getCell(4).getNumericCellValue()).isCloseTo(0.00000, within(0.000001));
            assertThat(primeraFila.getCell(5).getNumericCellValue()).isCloseTo(130.00000, within(0.000001));

            Row segundaFila = resumen.getRow(2);
            assertThat(segundaFila.getCell(2).getNumericCellValue()).isCloseTo(500.00000, within(0.000001));
            assertThat(segundaFila.getCell(5).getNumericCellValue()).isCloseTo(0.00000, within(0.000001));

            // Fila de total débito fiscal, inmediatamente después de las 2 filas de tarifa.
            Row filaTotal = resumen.getRow(3);
            assertThat(filaTotal.getCell(0).getStringCellValue()).isEqualTo("Total Débito Fiscal");
            assertThat(filaTotal.getCell(5).getNumericCellValue()).isCloseTo(130.00000, within(0.000001));
        }
    }

    @Test
    void hojaDetalleTieneUnaFilaPorTransaccionSinAgregar() throws IOException {
        // 2 lineas de la MISMA tarifa (13%) que en Resumen quedarian agregadas en una sola fila,
        // pero en Detalle deben aparecer SIN agregar -- una fila por transaccion.
        ReporteIvaResponse reporte = new ReporteIvaResponse(
                DESDE, HASTA,
                List.of(filaResumen(true, "13.00", "3000.00000", "390.00000", "0.00000", "390.00000")),
                List.of(
                        filaDetalle("00100001010000000001", "1000.00000", "130.00000", "130.00000", 1),
                        filaDetalle("00100001010000000002", "2000.00000", "260.00000", "260.00000", 1)),
                new BigDecimal("390.00000"));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet detalle = workbook.getSheet("Detalle");
            // Fila 0 = encabezado, filas 1 y 2 = las 2 transacciones, SIN agregar.
            assertThat(detalle.getLastRowNum()).isEqualTo(2);

            Row fila1 = detalle.getRow(1);
            assertThat(fila1.getCell(2).getStringCellValue()).isEqualTo("00100001010000000001");
            assertThat(fila1.getCell(10).getNumericCellValue()).isCloseTo(1000.00000, within(0.000001));
            assertThat(fila1.getCell(13).getNumericCellValue()).isCloseTo(130.00000, within(0.000001));

            Row fila2 = detalle.getRow(2);
            assertThat(fila2.getCell(2).getStringCellValue()).isEqualTo("00100001010000000002");
            assertThat(fila2.getCell(10).getNumericCellValue()).isCloseTo(2000.00000, within(0.000001));
            assertThat(fila2.getCell(13).getNumericCellValue()).isCloseTo(260.00000, within(0.000001));
        }
    }
}
