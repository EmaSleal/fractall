package cr.ac.fractall.facturacion.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.facturacion.servicio.ComprobanteElectronicoNoEncontradoException;
import lombok.extern.slf4j.Slf4j;

/**
 * Genera el PDF de una factura electrónica como {@code byte[]}.
 *
 * <p>Resuelve la empresa emisora exclusivamente vía {@code factura.empresa_id}
 * (FR-02 — nunca vía un "principal" implícito). No produce efectos secundarios:
 * sin persistencia, sin upload, sin {@code @Transactional}.
 *
 * <p>Usa Apache PDFBox 3.x ({@code Standard14Fonts.FontName} en lugar de los
 * constantes estáticos de la API 2.x).
 */
@Service
@Slf4j
public class FacturaPdfService {

    private static final DateTimeFormatter FECHA_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGEN_IZQ = 50f;
    private static final float MARGEN_DER = 545f;
    private static final float ANCHO_PAGINA = 595f;
    private static final float ALTO_PAGINA = 842f;  // A4
    private static final float MARGEN_INFERIOR = 50f;
    private static final int FUENTE_TITULO = 14;
    private static final int FUENTE_NORMAL = 10;
    private static final int FUENTE_PEQUENA = 9;
    private static final float INTERLINEA = 14f;

    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final ClienteRepository clienteRepository;
    private final ProductoRepository productoRepository;
    private final EmpresaRepository empresaRepository;

    public FacturaPdfService(
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            EmpresaRepository empresaRepository) {
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.empresaRepository = empresaRepository;
    }

    /**
     * Genera el PDF para el comprobante indicado y lo devuelve como {@code byte[]}.
     *
     * @param comprobanteId identificador del {@link ComprobanteElectronico}
     * @return PDF en bytes — siempre no vacío
     * @throws ComprobanteElectronicoNoEncontradoException si el comprobante no existe
     * @throws IllegalStateException si alguna FK interna no resuelve entidad
     */
    public byte[] generarPdf(UUID comprobanteId) {
        log.info("Generando PDF de factura electrónica para comprobante: {}", comprobanteId);

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository
                .findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

        Factura factura = facturaRepository.findById(comprobante.getFacturaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Factura no encontrada para comprobante " + comprobanteId
                                + ": " + comprobante.getFacturaId()));

        // FR-02: empresa resuelta SIEMPRE via factura.empresa_id
        Empresa empresa = empresaRepository.findById(factura.getEmpresaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Empresa no encontrada: " + factura.getEmpresaId()));

        Cliente cliente = clienteRepository.findById(factura.getClienteId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cliente no encontrado para factura " + factura.getId()
                                + ": " + factura.getClienteId()));

        List<LineaFactura> lineas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(factura.getId());

        try {
            return construirPdf(comprobante, factura, empresa, cliente, lineas);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Error generando PDF para comprobante " + comprobanteId, e);
        }
    }

    // -------------------------------------------------------------------------
    // PDF construction
    // -------------------------------------------------------------------------

    private byte[] construirPdf(
            ComprobanteElectronico comprobante,
            Factura factura,
            Empresa empresa,
            Cliente cliente,
            List<LineaFactura> lineas) throws IOException {

        PDType1Font fuenteBold =
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font fuenteNormal =
                new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        try (PDDocument doc = new PDDocument()) {
            PDPage pagina = new PDPage(PDRectangle.LETTER);
            doc.addPage(pagina);

            // Use LETTER page dimensions
            float anchoReal = pagina.getMediaBox().getWidth();
            float altoReal = pagina.getMediaBox().getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                Cursor cursor = new Cursor(altoReal - 50f);

                agregarEncabezado(cs, cursor, empresa, comprobante, fuenteBold, fuenteNormal);
                agregarBloqueCliente(cs, cursor, cliente, fuenteBold, fuenteNormal);
                agregarTablaLineas(cs, cursor, lineas, fuenteBold, fuenteNormal, anchoReal);
                agregarPie(cs, cursor, factura, fuenteBold, fuenteNormal);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private void agregarEncabezado(
            PDPageContentStream cs,
            Cursor cursor,
            Empresa empresa,
            ComprobanteElectronico comprobante,
            PDType1Font bold,
            PDType1Font normal) throws IOException {

        // Razon social
        escribirLinea(cs, cursor, empresa.getRazonSocial(), bold, FUENTE_TITULO);

        // Identificacion
        String tipoId = empresa.getTipoIdentificacion() != null
                ? empresa.getTipoIdentificacion() : "";
        escribirLinea(cs, cursor,
                tipoId + " " + nvl(empresa.getNumeroIdentificacion()),
                normal, FUENTE_NORMAL);

        // Actividad economica
        if (empresa.getCodigoActividad() != null) {
            escribirLinea(cs, cursor,
                    "Actividad: " + empresa.getCodigoActividad(),
                    normal, FUENTE_NORMAL);
        }

        // Email empresa
        if (empresa.getEmail() != null) {
            escribirLinea(cs, cursor, empresa.getEmail(), normal, FUENTE_NORMAL);
        }

        // Separador
        cursor.y -= 6f;

        // Datos del comprobante
        escribirLinea(cs, cursor,
                "Clave: " + comprobante.getClaveNumerica(),
                normal, FUENTE_PEQUENA);
        escribirLinea(cs, cursor,
                "Consecutivo: " + comprobante.getConsecutivo(),
                normal, FUENTE_PEQUENA);
        if (comprobante.getFechaEmision() != null) {
            escribirLinea(cs, cursor,
                    "Fecha emisión: " + comprobante.getFechaEmision().format(FECHA_FORMATTER),
                    normal, FUENTE_PEQUENA);
        }

        cursor.y -= 6f;
    }

    private void agregarBloqueCliente(
            PDPageContentStream cs,
            Cursor cursor,
            Cliente cliente,
            PDType1Font bold,
            PDType1Font normal) throws IOException {

        escribirLinea(cs, cursor, "Receptor:", bold, FUENTE_NORMAL);
        escribirLinea(cs, cursor, cliente.getNombre(), normal, FUENTE_NORMAL);
        escribirLinea(cs, cursor,
                nvl(cliente.getTipoIdentificacion()) + " " + nvl(cliente.getNumeroIdentificacion()),
                normal, FUENTE_NORMAL);

        // Email is optional — skip if null (FR-08 equivalent for PDF)
        if (cliente.getEmail() != null && !cliente.getEmail().isBlank()) {
            escribirLinea(cs, cursor, cliente.getEmail(), normal, FUENTE_NORMAL);
        }

        cursor.y -= 8f;
    }

    private void agregarTablaLineas(
            PDPageContentStream cs,
            Cursor cursor,
            List<LineaFactura> lineas,
            PDType1Font bold,
            PDType1Font normal,
            float anchoReal) throws IOException {

        // Header row
        escribirLinea(cs, cursor,
                "#  Descripción                        Cant    P.Unit     Subtotal  %Imp   Imp.     Total",
                bold, FUENTE_PEQUENA);
        linea(cs, cursor.y, anchoReal);
        cursor.y -= 4f;

        for (LineaFactura linea : lineas) {
            Producto producto = productoRepository.findById(linea.getProductoId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Producto no encontrado: " + linea.getProductoId()));

            BigDecimal subtotal = nvlDecimal(linea.getSubtotal());
            BigDecimal porcImp = nvlDecimal(linea.getPorcentajeImpuestoAplicado());
            BigDecimal montoImp = subtotal.multiply(porcImp)
                    .divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP);

            // Subtract exoneracion if present
            if (linea.getExoneracionId() != null
                    && linea.getMontoExoneracionAplicado() != null) {
                montoImp = montoImp.subtract(linea.getMontoExoneracionAplicado())
                        .max(BigDecimal.ZERO);
            }

            BigDecimal totalLinea = subtotal.add(montoImp);

            String descripcion = producto.getDescripcion();
            // Truncate to keep within column width
            if (descripcion.length() > 30) {
                descripcion = descripcion.substring(0, 30);
            }

            String fila = String.format(
                    "%-3d %-30s %6.2f %10.2f %10.2f %5.1f %8.2f %10.2f",
                    linea.getNumeroLinea(),
                    descripcion,
                    nvlDecimal(linea.getCantidad()).floatValue(),
                    nvlDecimal(linea.getPrecioUnitario()).floatValue(),
                    subtotal.floatValue(),
                    porcImp.floatValue(),
                    montoImp.floatValue(),
                    totalLinea.floatValue());

            escribirLinea(cs, cursor, fila, normal, FUENTE_PEQUENA);
        }

        cursor.y -= 6f;
    }

    private void agregarPie(
            PDPageContentStream cs,
            Cursor cursor,
            Factura factura,
            PDType1Font bold,
            PDType1Font normal) throws IOException {

        escribirLinea(cs, cursor,
                "Subtotal:         " + fmt(factura.getSubtotal()),
                normal, FUENTE_NORMAL);
        escribirLinea(cs, cursor,
                "Total impuesto:   " + fmt(factura.getTotalImpuesto()),
                normal, FUENTE_NORMAL);
        escribirLinea(cs, cursor,
                "Total:            " + fmt(factura.getTotal()),
                bold, FUENTE_NORMAL);

        cursor.y -= 8f;

        escribirLinea(cs, cursor,
                "Documento generado electrónicamente. Consulte en: https://www.hacienda.go.cr",
                normal, FUENTE_PEQUENA);
        escribirLinea(cs, cursor,
                "Factura Electrónica Autorizada",
                bold, FUENTE_PEQUENA);
    }

    // -------------------------------------------------------------------------
    // Low-level drawing helpers
    // -------------------------------------------------------------------------

    private void escribirLinea(
            PDPageContentStream cs,
            Cursor cursor,
            String texto,
            PDType1Font fuente,
            int tamano) throws IOException {

        cs.beginText();
        cs.setFont(fuente, tamano);
        cs.newLineAtOffset(MARGEN_IZQ, cursor.y);
        // PDFBox 3.x showText only accepts latin-1 (Type1 fonts); replace problematic chars
        cs.showText(sanitizar(texto));
        cs.endText();
        cursor.y -= INTERLINEA;
    }

    private void linea(PDPageContentStream cs, float y, float anchoReal) throws IOException {
        cs.moveTo(MARGEN_IZQ, y);
        cs.lineTo(anchoReal - MARGEN_IZQ, y);
        cs.stroke();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String nvl(String s) {
        return s != null ? s : "";
    }

    private BigDecimal nvlDecimal(BigDecimal d) {
        return d != null ? d : BigDecimal.ZERO;
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * PDFBox 3.x PDType1Font (Helvetica) only supports Windows-1252 / Latin-1.
     * Replace common Spanish characters that fall outside that range to avoid
     * {@code IllegalArgumentException} during showText.
     */
    private String sanitizar(String texto) {
        if (texto == null) return "";
        return texto
                .replace('ó', 'o')   // ó
                .replace('é', 'e')   // é
                .replace('á', 'a')   // á
                .replace('í', 'i')   // í
                .replace('ú', 'u')   // ú
                .replace('ñ', 'n')   // ñ
                .replace('Ó', 'O')   // Ó
                .replace('É', 'E')   // É
                .replace('Á', 'A')   // Á
                .replace('Í', 'I')   // Í
                .replace('Ú', 'U')   // Ú
                .replace('Ñ', 'N')   // Ñ
                .replace('ü', 'u')   // ü
                .replace('Ü', 'U')   // Ü
                .replace('à', 'a')   // à
                .replace('è', 'e')   // è
                .replace('ì', 'i')   // ì
                .replace('ò', 'o')   // ò
                .replace('ù', 'u')   // ù
                // Any remaining non-latin1 char → '?'
                .replaceAll("[^\\x00-\\xFF]", "?");
    }

    /** Mutable y-coordinate cursor passed by reference through helper methods. */
    private static class Cursor {
        float y;

        Cursor(float y) {
            this.y = y;
        }
    }
}
