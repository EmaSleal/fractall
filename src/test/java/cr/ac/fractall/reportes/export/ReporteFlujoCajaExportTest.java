package cr.ac.fractall.reportes.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

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
 * Prueba unitaria de {@link ReporteFlujoCajaExcelWriter} (Release 3 / Fase D, Change 2 de 2, PR6
 * -- ver el diseño obs #918 y {@code sdd/reporte-flujo-caja/tasks}, Fase 6). Plain JUnit + workbook
 * POI en memoria -- SIN Spring context, SIN Testcontainers, mismo criterio que
 * {@code ReporteIvaExportTest}: el writer es una función pura sobre un
 * {@link ReporteFlujoCajaResponse} ya construido; la forma real del fetch queda probada por
 * {@code ReporteFlujoCajaRepositoryIT}/{@code ReporteFlujoCajaServiceTest} (PR2-PR4).
 */
class ReporteFlujoCajaExportTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 8, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 8, 31);

    private FilaVentasPorCondicion filaVentasPorCondicion(String condicionVenta, long cantidad, String total) {
        return new FilaVentasPorCondicion(condicionVenta, cantidad, new BigDecimal(total));
    }

    private FilaCobrosPorMedioPago filaCobrosPorMedioPago(
            String medioPago, String descripcion, long cantidad, String total) {
        return new FilaCobrosPorMedioPago(medioPago, descripcion, cantidad, new BigDecimal(total));
    }

    private FilaDetalleVenta filaDetalleVenta(String consecutivo, String total, int signo) {
        return new FilaDetalleVenta(
                LocalDate.of(2026, 8, 15), "01", consecutivo, "02", UUID.randomUUID(), UUID.randomUUID(),
                null, "CRC", new BigDecimal(total), signo);
    }

    private FilaDetalleCobro filaDetalleCobro(UUID cobroId, String medioPago, String descripcion, String monto) {
        return new FilaDetalleCobro(
                LocalDate.of(2026, 8, 20), cobroId, UUID.randomUUID(), "00100001010000000001", "02",
                medioPago, descripcion, "ref-" + cobroId, new BigDecimal(monto));
    }

    private ReporteFlujoCajaResponse reporteConDetalle(
            List<FilaDetalleVenta> detalleVentas, List<FilaDetalleCobro> detalleCobros) {
        SerieVentas ventas = new SerieVentas(
                new BigDecimal("1130.00000"), 1, List.of(filaVentasPorCondicion("02", 1, "1130.00000")));
        SerieCobros cobros = new SerieCobros(
                new BigDecimal("500.00000"), 1,
                List.of(filaCobrosPorMedioPago("04", "Tarjeta", 1, "500.00000")));
        CarteraPendiente cartera = new CarteraPendiente(HASTA, new BigDecimal("630.00000"), 1);
        ComparativoPeriodoAnterior comparativo = new ComparativoPeriodoAnterior(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                new BigDecimal("900.00000"), new BigDecimal("400.00000"),
                new BigDecimal("230.00000"), new BigDecimal("100.00000"));
        return new ReporteFlujoCajaResponse(
                DESDE, HASTA, ventas, cobros, cartera, comparativo, detalleVentas, detalleCobros);
    }

    private XSSFWorkbook generarYLeer(ReporteFlujoCajaResponse reporte) throws IOException {
        byte[] bytes = ReporteFlujoCajaExcelWriter.generar(reporte);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    /** Busca la fila cuya primera celda sea EXACTAMENTE {@code texto} -- identifica un bloque del Resumen. */
    private Row filaConTexto(Sheet hoja, String texto) {
        for (Row fila : hoja) {
            Cell primeraCelda = fila.getCell(0);
            if (primeraCelda != null && primeraCelda.getCellType() == CellType.STRING
                    && texto.equals(primeraCelda.getStringCellValue())) {
                return fila;
            }
        }
        throw new AssertionError(
                "No se encontró una fila con texto '" + texto + "' en la hoja " + hoja.getSheetName());
    }

    @Test
    void excelTieneTresHojasResumenDetalleVentasYDetalleCobros() throws IOException {
        ReporteFlujoCajaResponse reporte = reporteConDetalle(
                List.of(filaDetalleVenta("00100001010000000001", "1130.00000", 1)),
                List.of(filaDetalleCobro(UUID.randomUUID(), "04", "Tarjeta", "500.00000")));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheetName(0)).isEqualTo("Resumen");
            assertThat(workbook.getSheetName(1)).isEqualTo("DetalleVentas");
            assertThat(workbook.getSheetName(2)).isEqualTo("DetalleCobros");
        }
    }

    @Test
    void hojaDetalleVentasTieneUnaFilaPorComprobanteSinAgregar() throws IOException {
        // 2 filas de la MISMA condicionVenta (02) que en Resumen quedarían agregadas en una sola
        // fila, pero en DetalleVentas deben aparecer SIN agregar -- una fila por comprobante.
        ReporteFlujoCajaResponse reporte = reporteConDetalle(
                List.of(
                        filaDetalleVenta("00100001010000000001", "1000.00000", 1),
                        filaDetalleVenta("00100001010000000002", "2000.00000", 1)),
                List.of(filaDetalleCobro(UUID.randomUUID(), "04", "Tarjeta", "500.00000")));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet detalleVentas = workbook.getSheet("DetalleVentas");
            // Fila 0 = encabezado, filas 1 y 2 = los 2 comprobantes, SIN agregar.
            assertThat(detalleVentas.getLastRowNum()).isEqualTo(2);

            Row fila1 = detalleVentas.getRow(1);
            assertThat(fila1.getCell(2).getStringCellValue()).isEqualTo("00100001010000000001");
            assertThat(fila1.getCell(8).getNumericCellValue()).isCloseTo(1000.00000, within(0.000001));

            Row fila2 = detalleVentas.getRow(2);
            assertThat(fila2.getCell(2).getStringCellValue()).isEqualTo("00100001010000000002");
            assertThat(fila2.getCell(8).getNumericCellValue()).isCloseTo(2000.00000, within(0.000001));
        }
    }

    @Test
    void hojaDetalleCobrosTieneUnaFilaPorCobroSinAgregar() throws IOException {
        UUID cobro1 = UUID.randomUUID();
        UUID cobro2 = UUID.randomUUID();
        // 2 cobros con el MISMO medioPago (04) que en Resumen quedarían agregados en una sola
        // fila, pero en DetalleCobros deben aparecer SIN agregar -- una fila por cobro.
        ReporteFlujoCajaResponse reporte = reporteConDetalle(
                List.of(filaDetalleVenta("00100001010000000001", "1130.00000", 1)),
                List.of(
                        filaDetalleCobro(cobro1, "04", "Tarjeta", "300.00000"),
                        filaDetalleCobro(cobro2, "04", "Tarjeta", "200.00000")));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet detalleCobros = workbook.getSheet("DetalleCobros");
            assertThat(detalleCobros.getLastRowNum()).isEqualTo(2);

            Row fila1 = detalleCobros.getRow(1);
            assertThat(fila1.getCell(8).getNumericCellValue()).isCloseTo(300.00000, within(0.000001));

            Row fila2 = detalleCobros.getRow(2);
            assertThat(fila2.getCell(8).getNumericCellValue()).isCloseTo(200.00000, within(0.000001));
        }
    }

    @Test
    void hojaDetalleCobrosLlevaMedioPagoYHojaDetalleVentasNo() throws IOException {
        ReporteFlujoCajaResponse reporte = reporteConDetalle(
                List.of(filaDetalleVenta("00100001010000000001", "1130.00000", 1)),
                List.of(filaDetalleCobro(UUID.randomUUID(), "04", "Tarjeta", "500.00000")));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet detalleCobros = workbook.getSheet("DetalleCobros");
            assertThat(contieneColumnaMedioPago(detalleCobros.getRow(0))).isTrue();

            Sheet detalleVentas = workbook.getSheet("DetalleVentas");
            assertThat(contieneColumnaMedioPago(detalleVentas.getRow(0))).isFalse();
        }
    }

    private boolean contieneColumnaMedioPago(Row encabezado) {
        for (Cell celda : encabezado) {
            if (celda.getStringCellValue().toLowerCase().contains("medio")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void hojaResumenIncluyeCarteraYComparativo() throws IOException {
        ReporteFlujoCajaResponse reporte = reporteConDetalle(
                List.of(filaDetalleVenta("00100001010000000001", "1130.00000", 1)),
                List.of(filaDetalleCobro(UUID.randomUUID(), "04", "Tarjeta", "500.00000")));

        try (XSSFWorkbook workbook = generarYLeer(reporte)) {
            Sheet resumen = workbook.getSheet("Resumen");

            Row filaCartera = filaConTexto(resumen, "CARTERA PENDIENTE");
            Row filaCarteraDatos = resumen.getRow(filaCartera.getRowNum() + 2);
            assertThat(filaCarteraDatos.getCell(0).getStringCellValue()).isEqualTo(HASTA.toString());
            assertThat(filaCarteraDatos.getCell(1).getNumericCellValue())
                    .isCloseTo(630.00000, within(0.000001));
            assertThat(filaCarteraDatos.getCell(2).getNumericCellValue()).isCloseTo(1, within(0.000001));

            Row filaComparativo = filaConTexto(resumen, "COMPARATIVO PERÍODO ANTERIOR");
            Row filaComparativoDatos = resumen.getRow(filaComparativo.getRowNum() + 2);
            assertThat(filaComparativoDatos.getCell(0).getStringCellValue())
                    .isEqualTo(LocalDate.of(2026, 7, 1).toString());
            assertThat(filaComparativoDatos.getCell(1).getStringCellValue())
                    .isEqualTo(LocalDate.of(2026, 7, 31).toString());
            assertThat(filaComparativoDatos.getCell(2).getNumericCellValue())
                    .isCloseTo(900.00000, within(0.000001));
            assertThat(filaComparativoDatos.getCell(3).getNumericCellValue())
                    .isCloseTo(400.00000, within(0.000001));
            assertThat(filaComparativoDatos.getCell(4).getNumericCellValue())
                    .isCloseTo(230.00000, within(0.000001));
            assertThat(filaComparativoDatos.getCell(5).getNumericCellValue())
                    .isCloseTo(100.00000, within(0.000001));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export PDF -- PR7 (ver sdd/reporte-flujo-caja/tasks, Fase 7; diseño obs #918, decisión B10)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fixture con 120 filas de DetalleVentas y 120 de DetalleCobros -- mismo orden de magnitud que
     * {@code ReporteIvaExportTest#reportePdfConFixtureGrande} (120 filas, ~53 líneas/página en A4
     * con la interlínea del writer), suficiente para forzar múltiples saltos de página DENTRO de
     * cada una de las dos secciones de detalle, no solo una.
     */
    private ReporteFlujoCajaResponse reportePdfConFixtureGrande() {
        List<FilaDetalleVenta> detalleVentas = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            detalleVentas.add(filaDetalleVenta(String.format("%020d", i), "1000.00000", 1));
        }
        List<FilaDetalleCobro> detalleCobros = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            detalleCobros.add(filaDetalleCobro(UUID.randomUUID(), "04", "Tarjeta", "500.00000"));
        }
        return reporteConDetalle(detalleVentas, detalleCobros);
    }

    private String textoDePagina(PDDocument doc, int numeroPagina) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(numeroPagina);
        stripper.setEndPage(numeroPagina);
        return stripper.getText(doc);
    }

    /**
     * Busca, en el rango {@code [desde, hasta]}, la PRIMERA página cuyo texto contenga
     * {@code marcador} -- usado para ubicar dinámicamente dónde empieza la sección DetalleCobros
     * sin asumir un número de página fijo (depende del tamaño del fixture y de cuántas líneas
     * caben por página). Retorna -1 si no se encuentra.
     */
    private int buscarPrimeraPaginaConTexto(PDDocument doc, String marcador, int desde, int hasta)
            throws IOException {
        for (int pagina = desde; pagina <= hasta; pagina++) {
            if (textoDePagina(doc, pagina).contains(marcador)) {
                return pagina;
            }
        }
        return -1;
    }

    /**
     * Requisito de diseño: página 1 = 4 bloques de Resumen; página 2+ = sección DetalleVentas
     * (encabezado repetible); página N+ = sección DetalleCobros, con su PROPIO encabezado
     * (decisión B10: reemplaza al de ventas vía el mecanismo de slot único de {@link CursorPdf}).
     * "MedioPago" solo aparece en el encabezado de DetalleCobros -- nunca en Resumen ni en
     * DetalleVentas -- así que su primera aparición marca el inicio de esa sección.
     */
    @Test
    void pdfConFixtureGrandeGeneraAlMenosCuatroPaginas() throws IOException {
        byte[] pdf = ReporteFlujoCajaPdfWriter.generar(reportePdfConFixtureGrande());

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int totalPaginas = doc.getNumberOfPages();
            assertThat(totalPaginas).isGreaterThanOrEqualTo(4);

            int paginaInicioCobros = buscarPrimeraPaginaConTexto(doc, "MedioPago", 2, totalPaginas);
            assertThat(paginaInicioCobros).as("debe existir una página de inicio de DetalleCobros").isPositive();

            int paginasVentas = paginaInicioCobros - 2;
            int paginasCobros = totalPaginas - paginaInicioCobros + 1;
            assertThat(paginasVentas).as("al menos 2 páginas de DetalleVentas").isGreaterThanOrEqualTo(2);
            assertThat(paginasCobros).as("al menos 1 página de DetalleCobros").isGreaterThanOrEqualTo(1);
        }
    }

    /**
     * "Signo" es una columna exclusiva del encabezado de DetalleVentas (nunca aparece en
     * DetalleCobros ni en Resumen) -- probar su presencia en TODAS las páginas de esa sección
     * confirma que {@link CursorPdf#registrarEncabezadoRepetible} lo reemite en cada salto de
     * página automático dentro de la sección, no solo en la primera.
     */
    @Test
    void encabezadoDeDetalleVentasSeRepiteEnSusPaginas() throws IOException {
        byte[] pdf = ReporteFlujoCajaPdfWriter.generar(reportePdfConFixtureGrande());

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int totalPaginas = doc.getNumberOfPages();
            int paginaInicioCobros = buscarPrimeraPaginaConTexto(doc, "MedioPago", 2, totalPaginas);
            // paginaInicioCobros > 3 asegura al menos 2 páginas de DetalleVentas (páginas 2 y 3).
            assertThat(paginaInicioCobros).isGreaterThan(3);

            for (int pagina = 2; pagina < paginaInicioCobros; pagina++) {
                assertThat(textoDePagina(doc, pagina))
                        .as("el encabezado de DetalleVentas debe repetirse en la página " + pagina)
                        .contains("Signo");
            }
        }
    }

    /**
     * Prueba central de la decisión B10: {@code CursorPdf} tiene un ÚNICO slot de encabezado
     * repetible -- re-registrarlo antes de DetalleCobros REEMPLAZA al de DetalleVentas. Si el
     * reemplazo no funcionara, las páginas de DetalleCobros seguirían mostrando el encabezado de
     * DetalleVentas ("Signo") en vez del propio ("MedioPago"). Se prueba en AL MENOS 2 páginas de
     * DetalleCobros para confirmar que la re-emisión ocurre en cada salto de página dentro de esa
     * sección, no solo en su primera página.
     */
    @Test
    void encabezadoDeDetalleCobrosSeRepiteEnSusPaginas() throws IOException {
        byte[] pdf = ReporteFlujoCajaPdfWriter.generar(reportePdfConFixtureGrande());

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int totalPaginas = doc.getNumberOfPages();
            int paginaInicioCobros = buscarPrimeraPaginaConTexto(doc, "MedioPago", 2, totalPaginas);
            assertThat(paginaInicioCobros).isPositive();
            assertThat(totalPaginas - paginaInicioCobros)
                    .as("al menos 2 páginas de DetalleCobros")
                    .isGreaterThanOrEqualTo(1);

            for (int pagina = paginaInicioCobros; pagina <= totalPaginas; pagina++) {
                assertThat(textoDePagina(doc, pagina))
                        .as("el encabezado de DetalleCobros debe repetirse en la página " + pagina)
                        .contains("MedioPago");
            }
        }
    }
}
