package cr.ac.fractall.reportes.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * Cobertura unitaria de {@link CursorPdf} — sin contexto Spring, sin Testcontainers.
 *
 * <p>Usa una página diminuta ({@code 200x300}) con margenes/interlinea grandes a propósito
 * para forzar el salto de página con pocas líneas escritas.
 */
class CursorPdfTest {

    private static final PDRectangle PAGINA_DIMINUTA = new PDRectangle(200f, 300f);
    private static final float MARGEN = 10f;
    private static final float INTERLINEA = 20f;

    @Test
    void escribirActivaSaltoDePaginaCuandoSuperaMargenInferior() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (CursorPdf cursor = new CursorPdf(
                    doc, PAGINA_DIMINUTA, MARGEN, MARGEN, MARGEN, INTERLINEA)) {

                assertThat(doc.getNumberOfPages()).isEqualTo(1);

                // Altura útil: 300 - 10 (margen sup) = 290. Con interlinea 20, caben 14 líneas
                // sin bajar del margen inferior (290 - 14*20 = 10, borde exacto). Escribimos
                // pocas líneas primero para probar que NO se activa el salto todavía.
                for (int i = 0; i < 5; i++) {
                    cursor.escribir("linea " + i, fuente, 10);
                }
                assertThat(doc.getNumberOfPages())
                        .as("texto que cabe no debe activar un salto de pagina")
                        .isEqualTo(1);

                // Seguimos escribiendo hasta superar el margen inferior real.
                for (int i = 5; i < 20; i++) {
                    cursor.escribir("linea " + i, fuente, 10);
                }
                assertThat(doc.getNumberOfPages())
                        .as("suficientes lineas deben forzar nuevaPagina()")
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void nuevaPaginaReemiteElEncabezadoRegistrado() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            AtomicInteger llamadasEncabezado = new AtomicInteger();

            try (CursorPdf cursor = new CursorPdf(
                    doc, PAGINA_DIMINUTA, MARGEN, MARGEN, MARGEN, INTERLINEA)) {

                cursor.registrarEncabezadoRepetible(() -> {
                    llamadasEncabezado.incrementAndGet();
                    cursor.escribir("Encabezado", fuente, 10);
                });

                assertThat(llamadasEncabezado.get())
                        .as("el encabezado no se emite al construir el cursor, solo tras un salto")
                        .isZero();

                // Forzamos dos saltos de pagina escribiendo suficientes lineas.
                for (int i = 0; i < 40; i++) {
                    cursor.escribir("linea " + i, fuente, 10);
                }

                assertThat(doc.getNumberOfPages()).isEqualTo(3);
                assertThat(llamadasEncabezado.get())
                        .as("el encabezado repetible debe reemitirse en cada pagina nueva")
                        .isEqualTo(2);
            }
        }
    }
}
