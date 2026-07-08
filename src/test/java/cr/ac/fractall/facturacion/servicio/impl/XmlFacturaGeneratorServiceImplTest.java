package cr.ac.fractall.facturacion.servicio.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.facturacion.servicio.XmlFacturaGeneratorService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) de
 * {@link XmlFacturaGeneratorServiceImpl#generarXmlFactura} -- mismo bootstrap Postgres-solo (sin
 * Vault) que {@code FacturaServiceTest}, ninguna ruta ejercitada aquí toca certificados ni
 * secretos de Vault.
 *
 * <p>Las entidades se construyen directamente vía repositorio (sin pasar por
 * {@code FacturaService}) para tener control total sobre los valores exactos que el generador
 * debe reflejar en el XML, incluyendo los dos casos límite que motivaron esta sub-tarea: una
 * línea con exoneración activa y el fallback de dirección a "Sin especificar".
 */
@Testcontainers
@SpringBootTest
class XmlFacturaGeneratorServiceImplTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private ClienteExoneracionRepository clienteExoneracionRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private LineaFacturaRepository lineaFacturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private XmlFacturaGeneratorService xmlFacturaGeneratorService;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba XML");
        usuario.setEmail("usuario-xml-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);
        usuarioId = usuario.getId();

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa XML S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setTipoIdentificacion("02");
        nueva.setCodigoActividad("620100");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("300 metros norte de la plaza central");
        nueva.setTelefono("22223333");
        nueva.setEmail("empresa@fractall.test");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Cliente crearCliente(String numeroIdentificacion, String otrasSenas) {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente " + numeroIdentificacion);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion(numeroIdentificacion);
        if (otrasSenas != null) {
            cliente.setCodigoProvincia("1");
            cliente.setCanton("01");
            cliente.setDistrito("01");
            cliente.setOtrasSenas(otrasSenas);
        }
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return clienteRepository.save(cliente);
    }

    private Producto crearProducto(String codigo, BigDecimal porcentajeImpuesto) {
        Producto producto = new Producto();
        producto.setCodigo(codigo);
        producto.setDescripcion("Producto " + codigo);
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(porcentajeImpuesto.compareTo(BigDecimal.ZERO) > 0);
        producto.setPorcentajeImpuesto(porcentajeImpuesto);
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        return productoRepository.save(producto);
    }

    private ClienteExoneracion crearExoneracion(UUID clienteId, BigDecimal porcentaje) {
        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setClienteId(clienteId);
        exoneracion.setTipoDocumento("08");
        exoneracion.setNumeroDocumento("DOC-" + UUID.randomUUID());
        exoneracion.setNombreInstitucion("PROCOMER");
        exoneracion.setNumeroArticulo("5");
        exoneracion.setInciso("2");
        exoneracion.setFechaEmision(LocalDateTime.now().minusDays(10));
        exoneracion.setPorcentajeExoneracion(porcentaje);
        exoneracion.setActivo(true);
        exoneracion.setCreateDate(LocalDateTime.now());
        exoneracion.setUpdateDate(LocalDateTime.now());
        return clienteExoneracionRepository.save(exoneracion);
    }

    private Factura crearFactura(UUID clienteId, BigDecimal subtotal, BigDecimal totalImpuesto) {
        Factura factura = new Factura();
        factura.setClienteId(clienteId);
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(BigDecimal.ONE);
        factura.setSubtotal(subtotal);
        factura.setTotalImpuesto(totalImpuesto);
        factura.setTotal(subtotal.add(totalImpuesto));
        factura.setCreadoPor(usuarioId);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        return facturaRepository.saveAndFlush(factura);
    }

    private ComprobanteElectronico crearComprobante(UUID facturaId) {
        String consecutivo = String.format("%020d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000_000_000_000L));
        // 50 dígitos numéricos únicos -- no necesita seguir el formato real de
        // ClaveNumericaGenerator, solo cumplir VARCHAR(50) UNIQUE (el generador ya no extrae el
        // consecutivo de la clave, ver la decisión #6 del javadoc de la clase bajo prueba).
        String relleno = UUID.randomUUID().toString().replaceAll("[^0-9]", "");
        while (relleno.length() < 30) {
            relleno = relleno + UUID.randomUUID().toString().replaceAll("[^0-9]", "");
        }
        String claveNumerica = ("506" + consecutivo + relleno).substring(0, 50);

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(facturaId);
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante("01");
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("GENERADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        return comprobanteElectronicoRepository.saveAndFlush(comprobante);
    }

    private LineaFactura crearLinea(UUID facturaId, Producto producto, int numeroLinea, BigDecimal cantidad,
            BigDecimal subtotal) {
        LineaFactura linea = new LineaFactura();
        linea.setFacturaId(facturaId);
        linea.setProductoId(producto.getId());
        linea.setNumeroLinea(numeroLinea);
        linea.setCantidad(cantidad);
        linea.setPrecioUnitario(producto.getPrecioVenta());
        linea.setSubtotal(subtotal);
        linea.setCodigoCabysAplicado(producto.getCodigoCabys());
        linea.setGravadoAplicado(producto.isGravado());
        linea.setPorcentajeImpuestoAplicado(producto.getPorcentajeImpuesto());
        return linea;
    }

    private Document parsear(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    }

    private String texto(Element padre, String tag) {
        NodeList nodos = padre.getElementsByTagName(tag);
        return nodos.getLength() > 0 ? nodos.item(0).getTextContent() : null;
    }

    @Test
    void generaXmlFacturaSinExoneracionNoIncluyeBloqueExoneracion() throws Exception {
        Cliente cliente = crearCliente("310199" + System.nanoTime() % 1_000_000, "100 metros este del parque");
        Producto producto = crearProducto("PROD-XML-A-" + UUID.randomUUID(), new BigDecimal("13.00"));

        Factura factura = crearFactura(cliente.getId(), new BigDecimal("1000.00000"), new BigDecimal("130.00000"));
        LineaFactura linea = crearLinea(factura.getId(), producto, 1, BigDecimal.ONE, new BigDecimal("1000.00000"));
        lineaFacturaRepository.saveAndFlush(linea);
        ComprobanteElectronico comprobante = crearComprobante(factura.getId());

        String xml = xmlFacturaGeneratorService.generarXmlFactura(comprobante.getId());

        assertThat(xml).doesNotContain("<Exoneracion>");
        assertThat(xml).contains("<Clave>" + comprobante.getClaveNumerica() + "</Clave>");
        assertThat(xml).contains("<NumeroConsecutivo>" + comprobante.getConsecutivo() + "</NumeroConsecutivo>");

        Document documento = parsear(xml);
        Element raiz = documento.getDocumentElement();
        assertThat(raiz.getTagName()).isEqualTo("FacturaElectronica");

        NodeList lineasDetalle = documento.getElementsByTagName("LineaDetalle");
        assertThat(lineasDetalle.getLength()).isEqualTo(1);
        Element lineaXml = (Element) lineasDetalle.item(0);
        assertThat(texto(lineaXml, "SubTotal")).isEqualTo("1000.00000");
        Element impuestoXml = (Element) lineaXml.getElementsByTagName("Impuesto").item(0);
        assertThat(texto(impuestoXml, "Monto")).isEqualTo("130.00000");
        assertThat(impuestoXml.getElementsByTagName("Exoneracion").getLength()).isZero();
        assertThat(texto(lineaXml, "ImpuestoNeto")).isEqualTo("130.00000");
        assertThat(texto(lineaXml, "MontoTotalLinea")).isEqualTo("1130.00000");

        Element resumen = (Element) documento.getElementsByTagName("ResumenFactura").item(0);
        assertThat(texto(resumen, "TotalExonerado")).isEqualTo("0.00000");
        assertThat(texto(resumen, "TotalImpuesto")).isEqualTo("130.00000");
        assertThat(texto(resumen, "TotalComprobante")).isEqualTo("1130.00000");
    }

    @Test
    void generaXmlFacturaConExoneracionEnUnaLineaIncluyeBloqueExoneracionConValoresCorrectos() throws Exception {
        Cliente cliente = crearCliente("310299" + System.nanoTime() % 1_000_000, "100 metros este del parque");
        Producto productoGravado = crearProducto("PROD-XML-B-" + UUID.randomUUID(), new BigDecimal("13.00"));
        Producto productoExonerado = crearProducto("PROD-XML-C-" + UUID.randomUUID(), new BigDecimal("13.00"));
        ClienteExoneracion exoneracion = crearExoneracion(cliente.getId(), new BigDecimal("100.00"));

        // Línea 1: sin exoneración, subtotal 1000, impuesto 130.
        // Línea 2: con exoneración 100% del impuesto, subtotal 2000, impuesto 260 -> exonerado 260.
        Factura factura = crearFactura(cliente.getId(), new BigDecimal("3000.00000"), new BigDecimal("130.00000"));
        LineaFactura linea1 = crearLinea(factura.getId(), productoGravado, 1, BigDecimal.ONE, new BigDecimal("1000.00000"));

        LineaFactura linea2 = crearLinea(factura.getId(), productoExonerado, 2, BigDecimal.ONE, new BigDecimal("2000.00000"));
        linea2.setExoneracionId(exoneracion.getId());
        linea2.setPorcentajeExoneracionAplicado(new BigDecimal("100.00"));
        linea2.setMontoExoneracionAplicado(new BigDecimal("260.00000"));

        lineaFacturaRepository.saveAll(java.util.List.of(linea1, linea2));
        lineaFacturaRepository.flush();
        ComprobanteElectronico comprobante = crearComprobante(factura.getId());

        String xml = xmlFacturaGeneratorService.generarXmlFactura(comprobante.getId());

        Document documento = parsear(xml);
        NodeList lineasDetalle = documento.getElementsByTagName("LineaDetalle");
        assertThat(lineasDetalle.getLength()).isEqualTo(2);

        Element lineaSinExoneracion = (Element) lineasDetalle.item(0);
        assertThat(((Element) lineaSinExoneracion.getElementsByTagName("Impuesto").item(0))
                .getElementsByTagName("Exoneracion").getLength()).isZero();

        Element lineaConExoneracion = (Element) lineasDetalle.item(1);
        Element impuesto2 = (Element) lineaConExoneracion.getElementsByTagName("Impuesto").item(0);
        NodeList exoneracionNodos = impuesto2.getElementsByTagName("Exoneracion");
        assertThat(exoneracionNodos.getLength()).isEqualTo(1);
        Element exoneracionXml = (Element) exoneracionNodos.item(0);

        assertThat(texto(exoneracionXml, "TipoDocumentoEX1")).isEqualTo("08");
        assertThat(exoneracionXml.getElementsByTagName("TipoDocumentoOTRO").getLength()).isZero();
        assertThat(texto(exoneracionXml, "NumeroDocumento")).isEqualTo(exoneracion.getNumeroDocumento());
        assertThat(texto(exoneracionXml, "Articulo")).isEqualTo("5");
        assertThat(texto(exoneracionXml, "Inciso")).isEqualTo("2");
        assertThat(texto(exoneracionXml, "NombreInstitucion")).isEqualTo("PROCOMER");
        assertThat(texto(exoneracionXml, "TarifaExonerada")).isEqualTo("100.00");
        assertThat(texto(exoneracionXml, "MontoExoneracion")).isEqualTo("260.00000");

        // Monto bruto del impuesto de la línea 2 sigue siendo 260 (antes de exonerar), pero el
        // ImpuestoNeto/MontoTotalLinea ya reflejan la exoneración (fórmula del XSD, ver el
        // javadoc de la clase).
        assertThat(texto(impuesto2, "Monto")).isEqualTo("260.00000");
        assertThat(texto(lineaConExoneracion, "ImpuestoNeto")).isEqualTo("0.00000");
        assertThat(texto(lineaConExoneracion, "MontoTotalLinea")).isEqualTo("2000.00000");

        Element resumen = (Element) documento.getElementsByTagName("ResumenFactura").item(0);
        assertThat(texto(resumen, "TotalExonerado")).isEqualTo("260.00000");
        // TotalImpuesto neto de la factura: (130 + 260) - 260 = 130.
        assertThat(texto(resumen, "TotalImpuesto")).isEqualTo("130.00000");
        assertThat(texto(resumen, "TotalComprobante")).isEqualTo("3130.00000");
    }

    @Test
    void otrasSenasEnBlancoEnEmpresaCaeAFallbackYEnClienteOmiteBloqueUbicacion() throws Exception {
        empresa.setOtrasSenas(null);
        empresaRepository.saveAndFlush(empresa);

        // Cliente sin dirección alguna: Ubicacion es opcional en el Receptor, así que el bloque
        // completo debe omitirse (a diferencia del Emisor, donde sí hay fallback textual).
        Cliente cliente = crearCliente("310399" + System.nanoTime() % 1_000_000, null);
        Producto producto = crearProducto("PROD-XML-D-" + UUID.randomUUID(), new BigDecimal("13.00"));

        Factura factura = crearFactura(cliente.getId(), new BigDecimal("1000.00000"), new BigDecimal("130.00000"));
        LineaFactura linea = crearLinea(factura.getId(), producto, 1, BigDecimal.ONE, new BigDecimal("1000.00000"));
        lineaFacturaRepository.saveAndFlush(linea);
        ComprobanteElectronico comprobante = crearComprobante(factura.getId());

        String xml = xmlFacturaGeneratorService.generarXmlFactura(comprobante.getId());

        Document documento = parsear(xml);
        Element emisor = (Element) documento.getElementsByTagName("Emisor").item(0);
        Element ubicacionEmisor = (Element) emisor.getElementsByTagName("Ubicacion").item(0);
        assertThat(texto(ubicacionEmisor, "OtrasSenas")).isEqualTo("Sin especificar");

        Element receptor = (Element) documento.getElementsByTagName("Receptor").item(0);
        assertThat(receptor.getElementsByTagName("Ubicacion").getLength()).isZero();
    }
}
