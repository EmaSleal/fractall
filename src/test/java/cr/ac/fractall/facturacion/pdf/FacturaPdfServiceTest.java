package cr.ac.fractall.facturacion.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;

/**
 * Pruebas unitarias (Mockito) de {@link FacturaPdfService}.
 *
 * <p>Triangulación:
 * <ol>
 *   <li>Happy path: 2 líneas, una con exoneración, una sin → byte[] no vacío con magic bytes %PDF.
 *   <li>Empresa resuelta via {@code factura.empresa_id}, NUNCA via fuente global (FR-02).
 *   <li>Clave numérica y consecutivo aparecen en el texto del PDF.
 *   <li>Cliente con email nulo → no lanza NPE.
 *   <li>3+ líneas → todas aparecen en el PDF.
 *   <li>Comprobante no encontrado → lanza excepción.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
// Some tests (e.g. generarComprobanteNoEncontradoLanzaExcepcion) exit early and don't
// consume every @BeforeEach stub; lenient avoids UnnecessaryStubbingException there.
@MockitoSettings(strictness = Strictness.LENIENT)
class FacturaPdfServiceTest {

    @Mock
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    @Mock
    private FacturaRepository facturaRepository;
    @Mock
    private LineaFacturaRepository lineaFacturaRepository;
    @Mock
    private ClienteRepository clienteRepository;
    @Mock
    private ProductoRepository productoRepository;
    @Mock
    private EmpresaRepository empresaRepository;
    @Mock
    private FacturaMedioPagoRepository facturaMedioPagoRepository;
    @Mock
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    @Mock
    private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    @Mock
    private ClienteExoneracionRepository clienteExoneracionRepository;

    @InjectMocks
    private FacturaPdfService servicio;

    private UUID comprobanteId;
    private UUID facturaId;
    private UUID empresaId;
    private UUID clienteId;
    private UUID productoAId;
    private UUID productoBId;

    private ComprobanteElectronico comprobante;
    private Factura factura;
    private Empresa empresa;
    private Cliente cliente;
    private Producto productoA;
    private Producto productoB;

    @BeforeEach
    void setUp() {
        comprobanteId = UUID.randomUUID();
        facturaId = UUID.randomUUID();
        empresaId = UUID.randomUUID();
        clienteId = UUID.randomUUID();
        productoAId = UUID.randomUUID();
        productoBId = UUID.randomUUID();

        comprobante = stubComprobante(comprobanteId, facturaId, empresaId);
        factura = stubFactura(facturaId, clienteId, empresaId);
        empresa = stubEmpresa(empresaId, "Empresa Emisora S.A.", "3101234567890",
                "620100", "empresa@test.com");
        cliente = stubCliente(clienteId, "Juan Pérez", "02", "310123456789",
                "juan@test.com");
        productoA = stubProducto(productoAId, "Servicio de consultoría");
        productoB = stubProducto(productoBId, "Licencia de software");

        // Default happy-path stubs
        when(comprobanteElectronicoRepository.findById(comprobanteId))
                .thenReturn(Optional.of(comprobante));
        when(facturaRepository.findById(facturaId))
                .thenReturn(Optional.of(factura));
        when(empresaRepository.findById(empresaId))
                .thenReturn(Optional.of(empresa));
        when(clienteRepository.findById(clienteId))
                .thenReturn(Optional.of(cliente));
        when(facturaMedioPagoRepository.findByFacturaIdOrderByOrden(facturaId))
                .thenReturn(List.of());
        when(facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(facturaId))
                .thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // Test 1: happy path — 2 lineas, una con exoneracion, PDF válido
    // -------------------------------------------------------------------------
    @Test
    void generarDevuelveByteArrayNoVacioConMagicBytesPdf() throws Exception {
        LineaFactura lineaSin = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        LineaFactura lineaCon = stubLinea(facturaId, productoBId, 2,
                new BigDecimal("2000.00000"), new BigDecimal("260.00000"), UUID.randomUUID());

        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(lineaSin, lineaCon));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));
        when(productoRepository.findById(productoBId))
                .thenReturn(Optional.of(productoB));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        assertThat(pdf).isNotEmpty();
        // %PDF magic bytes
        assertThat(new String(Arrays.copyOf(pdf, 4))).isEqualTo("%PDF");
        // Verify the byte array is a loadable PDF (PDFBox 3.x uses Loader.loadPDF)
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: empresa resuelta via factura.empresa_id, NUNCA via fuente global
    // -------------------------------------------------------------------------
    @Test
    void generarUsaEmpresaDeLaFacturaNoDeFuenteGlobal() {
        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        // Second empresa ID — must never be called (FR-02: empresa comes from factura only)
        UUID otraEmpresaId = UUID.randomUUID();

        byte[] pdf = servicio.generarPdf(comprobanteId);

        // Must call findById with the empresa_id that came from factura
        verify(empresaRepository).findById(empresaId);
        // Must NOT call findById with any other empresa id
        verify(empresaRepository, never()).findById(otraEmpresaId);

        // The PDF must contain the correct empresa razon social
        String texto = extractText(pdf);
        assertThat(texto).contains("Empresa Emisora S.A.");
    }

    // -------------------------------------------------------------------------
    // Test 3: clave numerica y consecutivo en el texto del PDF
    // -------------------------------------------------------------------------
    @Test
    void generarIncluirClaveNumericaYConsecutivoEnTexto() throws Exception {
        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains(comprobante.getClaveNumerica());
        assertThat(texto).contains(comprobante.getConsecutivo());
    }

    // -------------------------------------------------------------------------
    // Test 4: cliente con email nulo — no lanza NPE
    // -------------------------------------------------------------------------
    @Test
    void generarConEmailClienteNuloNoLanza() {
        cliente.setEmail(null);

        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(Arrays.copyOf(pdf, 4))).isEqualTo("%PDF");
    }

    // -------------------------------------------------------------------------
    // Test 4b (Release 2 / Fase C): factura.clienteId nulo — Tiquete sin receptor identificado.
    // Hallazgo real de integración: clienteRepository.findById(factura.getClienteId()) era
    // incondicional -- con clienteId null, Spring Data lanza InvalidDataAccessApiUsageException
    // en vez de tratarlo como "sin cliente". Confirmado end-to-end en TiqueteEmisionIT.
    // -------------------------------------------------------------------------
    @Test
    void generarConFacturaClienteIdNuloMuestraConsumidorFinalYNoLanza() {
        factura.setClienteId(null);

        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        assertThat(pdf).isNotEmpty();
        String texto = extractText(pdf);
        assertThat(texto).contains("Consumidor Final");
        assertThat(texto).doesNotContain(cliente.getNombre());
    }

    // -------------------------------------------------------------------------
    // Test 5: 3+ lineas — todas las descripciones de producto en el PDF
    // -------------------------------------------------------------------------
    @Test
    void generarConMultiplesLineasTodasAparecenEnElPdf() {
        UUID productoCId = UUID.randomUUID();
        Producto productoC = stubProducto(productoCId, "Mantenimiento anual");

        LineaFactura linea1 = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        LineaFactura linea2 = stubLinea(facturaId, productoBId, 2,
                new BigDecimal("2000.00000"), BigDecimal.ZERO, null);
        LineaFactura linea3 = stubLinea(facturaId, productoCId, 3,
                new BigDecimal("500.00000"), BigDecimal.ZERO, null);

        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea1, linea2, linea3));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));
        when(productoRepository.findById(productoBId))
                .thenReturn(Optional.of(productoB));
        when(productoRepository.findById(productoCId))
                .thenReturn(Optional.of(productoC));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        // Service sanitizes accented chars for Latin-1 PDFBox font; assert the sanitized form
        assertThat(texto).contains("Servicio de consultoria");
        assertThat(texto).contains("Licencia de software");
        assertThat(texto).contains("Mantenimiento anual");
    }

    // -------------------------------------------------------------------------
    // Test 6: comprobante no encontrado — lanza excepción
    // -------------------------------------------------------------------------
    @Test
    void generarComprobanteNoEncontradoLanzaExcepcion() {
        UUID noExiste = UUID.randomUUID();
        when(comprobanteElectronicoRepository.findById(noExiste))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.generarPdf(noExiste))
                .isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------
    // Test 7: linea con exoneracion no lanza
    // -------------------------------------------------------------------------
    @Test
    void generarConExoneracionEnLineaNoLanza() {
        LineaFactura lineaConExoneracion = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), new BigDecimal("130.00000"), UUID.randomUUID());
        lineaConExoneracion.setPorcentajeExoneracionAplicado(new BigDecimal("50.00"));

        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(lineaConExoneracion));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        assertThat(pdf).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 8: tipo de documento (catálogo Hacienda) impreso en el encabezado y el pie
    // -------------------------------------------------------------------------
    @Test
    void generarIncluyeTipoDeDocumentoEnEncabezadoYPie() {
        comprobante.setTipoComprobante("03");

        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Nota de Credito Electronica");
    }

    // -------------------------------------------------------------------------
    // Test 9: condicion de venta y moneda en el PDF
    // -------------------------------------------------------------------------
    @Test
    void generarIncluyeCondicionDeVentaYMoneda() {
        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Contado");
        assertThat(texto).contains("CRC");
    }

    // -------------------------------------------------------------------------
    // Test 10: sin filas en factura_medio_pago -- fallback al medio_pago escalar legacy
    // -------------------------------------------------------------------------
    @Test
    void generarSinFilasMedioPagoUsaFallbackLegacyDeFactura() {
        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Efectivo");
    }

    // -------------------------------------------------------------------------
    // Test 11: multiples filas en factura_medio_pago -- todas aparecen
    // -------------------------------------------------------------------------
    @Test
    void generarConMultiplesMediosPagoListaTodos() {
        FacturaMedioPago mp1 = new FacturaMedioPago();
        mp1.setFacturaId(facturaId);
        mp1.setOrden((short) 1);
        mp1.setTipoMedioPago("02");
        mp1.setTotalMedioPago(new BigDecimal("2000.00000"));

        FacturaMedioPago mp2 = new FacturaMedioPago();
        mp2.setFacturaId(facturaId);
        mp2.setOrden((short) 2);
        mp2.setTipoMedioPago("06");
        mp2.setTotalMedioPago(new BigDecimal("1130.00000"));

        when(facturaMedioPagoRepository.findByFacturaIdOrderByOrden(facturaId))
                .thenReturn(List.of(mp1, mp2));

        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Tarjeta");
        assertThat(texto).contains("SINPE Movil");
    }

    // -------------------------------------------------------------------------
    // Test 12: informacion de referencia (NC/ND) impresa cuando existe
    // -------------------------------------------------------------------------
    @Test
    void generarConInformacionReferenciaMuestraDocumentoOrigen() {
        comprobante.setTipoComprobante("03");

        FacturaInformacionReferencia referencia = new FacturaInformacionReferencia();
        referencia.setFacturaId(facturaId);
        referencia.setOrden((short) 1);
        referencia.setTipoDocIr("01");
        referencia.setNumero("00100001010000000005");
        referencia.setFechaEmisionIr(LocalDateTime.of(2026, 8, 1, 9, 0, 0));
        referencia.setCodigo("06");
        referencia.setRazon("Devolucion de mercaderia dañada");

        when(facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(facturaId))
                .thenReturn(List.of(referencia));

        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), BigDecimal.ZERO, null);
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("00100001010000000005");
        assertThat(texto).contains("Devolucion de mercaderia da");
    }

    // -------------------------------------------------------------------------
    // Test 13: exoneracion inline (bloque ExoneracionRequest) -- detalle completo por linea
    //
    // Reconstruido (discovery 4 del diseño): la version anterior de este test combinaba
    // exoneracionId != null CON una fila ImpuestoLineaExoneracion -- una forma que
    // LineaFacturaEnsamblador:150 nunca produce en produccion (las columnas legacy
    // quedan en null cuando la exoneracion es inline). Se reconstruye con
    // stubLineaConExoneracionInline (ambas columnas legacy en null, solo la fila inline).
    // -------------------------------------------------------------------------
    @Test
    void generarConExoneracionInlineMuestraInstitucionYMontoPorLinea() {
        LineaFactura linea = stubLineaConExoneracionInline(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        ImpuestoLineaExoneracion exoneracionInline = new ImpuestoLineaExoneracion();
        exoneracionInline.setLineaId(linea.getId());
        exoneracionInline.setTipoDocumentoEx1("01");
        exoneracionInline.setNumeroDocumento("EX-2026-001");
        exoneracionInline.setNombreInstitucion("01");
        exoneracionInline.setFechaEmisionEx(LocalDateTime.of(2026, 1, 10, 0, 0, 0));
        exoneracionInline.setTarifaExonerada(new BigDecimal("13.00"));
        exoneracionInline.setMontoExoneracion(new BigDecimal("130.00000"));
        when(impuestoLineaExoneracionRepository.findByLineaId(linea.getId()))
                .thenReturn(Optional.of(exoneracionInline));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Ministerio de Hacienda");
        assertThat(texto).contains("EX-2026-001");
        assertThat(texto).contains("130.00");
    }

    // -------------------------------------------------------------------------
    // Test 13b (RED para el fix del defecto real): la version inline-only de una
    // exoneracion debe restarse del impuesto de la linea. Antes del fix,
    // linea.getExoneracionId() es null (forma real de produccion) asi que el guard de
    // ":439" nunca dispara y el impuesto/total de la linea se imprimen SIN restar el
    // monto exonerado -- esto es el defecto real en facturas PDF ya emitidas.
    // -------------------------------------------------------------------------
    @Test
    void generarConExoneracionInlineRestaElMontoDelImpuestoDeLaLinea() {
        LineaFactura linea = stubLineaConExoneracionInline(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        ImpuestoLineaExoneracion exoneracionInline = new ImpuestoLineaExoneracion();
        exoneracionInline.setLineaId(linea.getId());
        exoneracionInline.setNombreInstitucion("01");
        exoneracionInline.setMontoExoneracion(new BigDecimal("130.00000"));
        when(impuestoLineaExoneracionRepository.findByLineaId(linea.getId()))
                .thenReturn(Optional.of(exoneracionInline));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        // Columnas %Imp / Imp. / Total de la fila: 13.0% de impuesto bruto, pero neto en
        // 0.00 (1000.00 x 13% = 130.00, menos 130.00 exonerado inline) y total = subtotal.
        String segmentoEsperado = String.format("%5.1f %8.2f %10.2f", 13.0f, 0.00f, 1000.00f);
        assertThat(texto).contains(segmentoEsperado);
    }

    // -------------------------------------------------------------------------
    // Test 13c (pin discovery 6 del diseño): al repuntar a CalculadoraImpuestoLinea se
    // remueve silenciosamente el piso .max(ZERO) que FacturaPdfService aplicaba antes --
    // un monto exonerado mayor al impuesto bruto debe imprimirse NEGATIVO, sin piso.
    // -------------------------------------------------------------------------
    @Test
    void generarConExoneracionInlineMayorAlImpuestoNoAplicaPiso() {
        LineaFactura linea = stubLineaConExoneracionInline(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        ImpuestoLineaExoneracion exoneracionInline = new ImpuestoLineaExoneracion();
        exoneracionInline.setLineaId(linea.getId());
        exoneracionInline.setNombreInstitucion("01");
        exoneracionInline.setMontoExoneracion(new BigDecimal("200.00000"));
        when(impuestoLineaExoneracionRepository.findByLineaId(linea.getId()))
                .thenReturn(Optional.of(exoneracionInline));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        // 1000.00 x 13% = 130.00 bruto; 130.00 - 200.00 = -70.00 neto (sin piso en cero);
        // total de linea = 1000.00 + (-70.00) = 930.00.
        String segmentoEsperado = String.format("%5.1f %8.2f %10.2f", 13.0f, -70.00f, 930.00f);
        assertThat(texto).contains(segmentoEsperado);
    }

    // -------------------------------------------------------------------------
    // Test 14: exoneracion legacy (ClienteExoneracion via exoneracionId) -- detalle por linea
    // -------------------------------------------------------------------------
    @Test
    void generarConExoneracionLegacyMuestraInstitucionDelClienteExonerado() {
        UUID clienteExoneracionId = UUID.randomUUID();
        LineaFactura linea = stubLinea(facturaId, productoAId, 1,
                new BigDecimal("1000.00000"), new BigDecimal("65.00000"), clienteExoneracionId);
        linea.setPorcentajeExoneracionAplicado(new BigDecimal("50.00"));

        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId))
                .thenReturn(List.of(linea));
        when(productoRepository.findById(productoAId))
                .thenReturn(Optional.of(productoA));

        ClienteExoneracion exoneracionCliente = new ClienteExoneracion();
        exoneracionCliente.setNombreInstitucion("Ministerio de Salud");
        exoneracionCliente.setNumeroDocumento("DOC-LEGACY-9");
        when(clienteExoneracionRepository.findById(clienteExoneracionId))
                .thenReturn(Optional.of(exoneracionCliente));

        byte[] pdf = servicio.generarPdf(comprobanteId);

        String texto = extractText(pdf);
        assertThat(texto).contains("Ministerio de Salud");
        assertThat(texto).contains("DOC-LEGACY-9");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a ComprobanteElectronico stub without relying on Lombok-generated
     * setters for the id/empresaId fields (they come from TenantAwareEntity /
     * EntidadBase, which use generated values). We set only what FacturaPdfService
     * actually reads via getters.
     */
    private ComprobanteElectronico stubComprobante(UUID id, UUID factId, UUID empId) {
        ComprobanteElectronico c = new ComprobanteElectronico();
        // Use ReflectionTestUtils-style field writes since id/empresaId have no setters
        setField(c, "id", id);
        setField(c, "empresaId", empId);
        c.setFacturaId(factId);
        c.setClaveNumerica("50601011500310310001000000001411234567890123456789012");
        c.setConsecutivo("00100001010000000001");
        c.setFechaEmision(LocalDateTime.of(2025, 6, 15, 10, 30, 0));
        c.setEstado("ACEPTADO");
        c.setConsecutivo("00100001010000000099");
        c.setClaveNumerica("50601011500310310001000000001411234567890123456789012");
        c.setTipoComprobante("01");
        return c;
    }

    private Factura stubFactura(UUID id, UUID cliId, UUID empId) {
        Factura f = new Factura();
        setField(f, "id", id);
        setField(f, "empresaId", empId);
        f.setClienteId(cliId);
        f.setSubtotal(new BigDecimal("3000.00000"));
        f.setTotalImpuesto(new BigDecimal("130.00000"));
        f.setTotal(new BigDecimal("3130.00000"));
        f.setCondicionVenta("01");
        f.setMedioPago("01");
        f.setMoneda("CRC");
        f.setTipoCambio(BigDecimal.ONE);
        f.setCreateDate(LocalDateTime.now());
        f.setUpdateDate(LocalDateTime.now());
        return f;
    }

    private Empresa stubEmpresa(UUID id, String razonSocial, String numeroId,
            String codigoActividad, String email) {
        Empresa e = new Empresa();
        setField(e, "id", id);
        e.setRazonSocial(razonSocial);
        e.setNumeroIdentificacion(numeroId);
        e.setTipoIdentificacion("02");
        e.setCodigoActividad(codigoActividad);
        e.setEmail(email);
        e.setAmbienteHacienda("SANDBOX");
        e.setStatus("REGISTRADA");
        return e;
    }

    private Cliente stubCliente(UUID id, String nombre, String tipoId,
            String numeroId, String email) {
        Cliente c = new Cliente();
        setField(c, "id", id);
        c.setNombre(nombre);
        c.setTipoIdentificacion(tipoId);
        c.setNumeroIdentificacion(numeroId);
        c.setEmail(email);
        c.setRequiereFacturaElectronica(true);
        c.setCreateDate(LocalDateTime.now());
        c.setUpdateDate(LocalDateTime.now());
        return c;
    }

    private Producto stubProducto(UUID id, String descripcion) {
        Producto p = new Producto();
        setField(p, "id", id);
        p.setDescripcion(descripcion);
        p.setCodigo("PROD-TEST");
        p.setCodigoCabys("2132100000100");
        p.setPrecioVenta(new BigDecimal("1000.00000"));
        p.setGravado(true);
        p.setPorcentajeImpuesto(new BigDecimal("13.00"));
        p.setActivo(true);
        p.setCreateDate(LocalDateTime.now());
        p.setUpdateDate(LocalDateTime.now());
        return p;
    }

    private LineaFactura stubLinea(UUID factId, UUID prodId, int numero,
            BigDecimal subtotal, BigDecimal montoExoneracion, UUID exoneracionId) {
        LineaFactura l = new LineaFactura();
        setField(l, "id", UUID.randomUUID());
        l.setFacturaId(factId);
        l.setProductoId(prodId);
        l.setNumeroLinea(numero);
        l.setCantidad(BigDecimal.ONE);
        l.setPrecioUnitario(subtotal);
        l.setSubtotal(subtotal);
        l.setCodigoCabysAplicado("2132100000100");
        l.setGravadoAplicado(true);
        l.setPorcentajeImpuestoAplicado(new BigDecimal("13.00"));
        l.setExoneracionId(exoneracionId);
        if (exoneracionId != null) {
            l.setMontoExoneracionAplicado(montoExoneracion);
            l.setPorcentajeExoneracionAplicado(new BigDecimal("100.00"));
        }
        return l;
    }

    /**
     * Linea exonerada por la via INLINE real de produccion: ambas columnas legacy
     * ({@code exoneracionId}, {@code montoExoneracionAplicado}) quedan en null -- la
     * exoneracion vive exclusivamente en la fila {@code ImpuestoLineaExoneracion}
     * (ver {@code LineaFacturaEnsamblador:150}). La combinacion "exoneracionId no nulo
     * Y fila inline presente" (usada por el fixture anterior de este test) es
     * estructuralmente imposible en produccion -- ver design discovery 4.
     */
    private LineaFactura stubLineaConExoneracionInline(UUID factId, UUID prodId, int numero,
            BigDecimal subtotal) {
        LineaFactura l = new LineaFactura();
        setField(l, "id", UUID.randomUUID());
        l.setFacturaId(factId);
        l.setProductoId(prodId);
        l.setNumeroLinea(numero);
        l.setCantidad(BigDecimal.ONE);
        l.setPrecioUnitario(subtotal);
        l.setSubtotal(subtotal);
        l.setCodigoCabysAplicado("2132100000100");
        l.setGravadoAplicado(true);
        l.setPorcentajeImpuestoAplicado(new BigDecimal("13.00"));
        l.setExoneracionId(null);
        l.setMontoExoneracionAplicado(null);
        return l;
    }

    private String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract PDF text", e);
        }
    }

    /**
     * Sets a private field on an entity (needed because id/empresaId have no setters
     * — they are generated-at-insert values managed by Hibernate, see EntidadBase /
     * TenantAwareEntity).
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + fieldName + " on " + target.getClass(), e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
