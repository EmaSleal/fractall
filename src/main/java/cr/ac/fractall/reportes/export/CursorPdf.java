package cr.ac.fractall.reportes.export;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Cursor de escritura consciente de saltos de página, para reportes PDF de múltiples páginas.
 *
 * <p>Encapsula el {@link PDPageContentStream} activo y una posición vertical ({@code y}).
 * {@link #escribir} salta automáticamente a una página nueva cuando el siguiente renglón no
 * cabría por encima del margen inferior: cierra el content stream actual, agrega una página al
 * {@link PDDocument}, abre un content stream nuevo, reinicia {@code y} al margen superior y
 * reemite el encabezado repetible registrado (si existe) antes de continuar con la escritura
 * original.
 *
 * <p>Reproduce los mismos idiomas de PDFBox 3.0.7 ya establecidos en {@code FacturaPdfService}:
 * construcción de {@link PDType1Font} vía {@code Standard14Fonts} (nunca las constantes
 * estáticas de la API 2.x), la secuencia {@code beginText/setFont/newLineAtOffset/showText/
 * endText}, y el saneo Latin-1 antes de {@code showText} ({@code PDType1Font#showText} lanza
 * {@link IllegalArgumentException} con texto fuera de Latin-1).
 *
 * <p>Por decisión de diseño A6, esta clase NO se acopla a {@code FacturaPdfService}: una factura
 * es un artefacto fiscal congelado, mientras que este reporte es una integración evolutiva
 * distinta. {@link #sanitizar(String)} se duplica deliberadamente en vez de importarse — el
 * costo de reutilizar un helper {@code private} de 20 líneas es mayor que el de duplicarlo.
 */
final class CursorPdf implements AutoCloseable {

    /** Callback re-emitido después de cada salto de página, vía {@link #nuevaPagina()}. */
    @FunctionalInterface
    interface EncabezadoRepetible {
        void escribir() throws IOException;
    }

    private final PDDocument doc;
    private final PDRectangle tamano;
    private final float margenIzq;
    private final float margenSup;
    private final float margenInf;
    private final float interlinea;

    private PDPageContentStream cs;
    private float y;
    private EncabezadoRepetible encabezadoRepetible;

    CursorPdf(
            PDDocument doc,
            PDRectangle tamano,
            float margenIzq,
            float margenSup,
            float margenInf,
            float interlinea) throws IOException {
        this.doc = doc;
        this.tamano = tamano;
        this.margenIzq = margenIzq;
        this.margenSup = margenSup;
        this.margenInf = margenInf;
        this.interlinea = interlinea;

        PDPage pagina = new PDPage(tamano);
        doc.addPage(pagina);
        this.cs = new PDPageContentStream(doc, pagina);
        this.y = tamano.getHeight() - margenSup;
    }

    /** Registra el encabezado a reemitir después de cada salto de página futuro. */
    void registrarEncabezadoRepetible(EncabezadoRepetible encabezadoRepetible) {
        this.encabezadoRepetible = encabezadoRepetible;
    }

    /** Escribe una línea de texto en la posición actual, saltando de página si no cabe. */
    void escribir(String texto, PDType1Font fuente, int tamanoFuente) throws IOException {
        if (y - interlinea < margenInf) {
            nuevaPagina();
        }
        cs.beginText();
        cs.setFont(fuente, tamanoFuente);
        cs.newLineAtOffset(margenIzq, y);
        cs.showText(sanitizar(texto));
        cs.endText();
        y -= interlinea;
    }

    /** Desplaza el cursor verticalmente sin escribir texto (separadores entre secciones). */
    void espacio(float px) {
        y -= px;
    }

    /** Dibuja una línea horizontal completa a la altura actual del cursor. */
    void lineaHorizontal() throws IOException {
        cs.moveTo(margenIzq, y);
        cs.lineTo(tamano.getWidth() - margenIzq, y);
        cs.stroke();
    }

    /** Cierra la página actual, abre una nueva y reemite el encabezado repetible registrado. */
    void nuevaPagina() throws IOException {
        cs.close();
        PDPage pagina = new PDPage(tamano);
        doc.addPage(pagina);
        cs = new PDPageContentStream(doc, pagina);
        y = tamano.getHeight() - margenSup;
        if (encabezadoRepetible != null) {
            encabezadoRepetible.escribir();
        }
    }

    @Override
    public void close() throws IOException {
        cs.close();
    }

    /**
     * PDFBox 3.x {@link PDType1Font} (Helvetica) sólo soporta Windows-1252 / Latin-1. Reemplaza
     * los caracteres españoles comunes fuera de ese rango para evitar {@link
     * IllegalArgumentException} durante {@code showText}.
     *
     * <p>Duplicado deliberado de {@code FacturaPdfService#sanitizar} — ver decisión de diseño A6.
     */
    private String sanitizar(String texto) {
        if (texto == null) return "";
        return texto
                .replace('ó', 'o')
                .replace('é', 'e')
                .replace('á', 'a')
                .replace('í', 'i')
                .replace('ú', 'u')
                .replace('ñ', 'n')
                .replace('Ó', 'O')
                .replace('É', 'E')
                .replace('Á', 'A')
                .replace('Í', 'I')
                .replace('Ú', 'U')
                .replace('Ñ', 'N')
                .replace('ü', 'u')
                .replace('Ü', 'U')
                .replace('à', 'a')
                .replace('è', 'e')
                .replace('ì', 'i')
                .replace('ò', 'o')
                .replace('ù', 'u')
                .replaceAll("[^\\x00-\\xFF]", "?");
    }
}
