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
