package cr.ac.fractall.facturacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CodigoComercialRequest;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.DescuentoRequest;
import cr.ac.fractall.facturacion.dto.ExoneracionRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.IdentificacionTerceroRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.MedioPagoRequest;
import cr.ac.fractall.facturacion.dto.OtrosCargoRequest;
import cr.ac.fractall.facturacion.dto.ReferenciaRequest;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.facturacion.servicio.XmlFacturaGeneratorService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración extremo a extremo del flujo completo de campos FE v4.4 (fe-campos-completos):
 * POST /facturas con todos los campos nuevos poblados → persistencia en DB → XML generado → XSD válido.
 *
 * <p>Cubre el criterio de salida de la fase de apply del SDD fe-campos-completos:
 * <ul>
 *   <li>CodigosComerciales (2) y Descuentos (1) en LineaDetalle → reduce base imponible</li>
 *   <li>Exoneracion inline (ImpuestoLineaExoneracion) en vez de ruta legacy ClienteExoneracion</li>
 *   <li>tipoTransaccion, codigoActividadReceptor, condicionVentaOtros, totalIvaDevuelto almacenados</li>
 *   <li>OtrosCargos (2 filas) persistidos con orden correcto</li>
 *   <li>InformacionReferencia (2 filas) persistidos con orden correcto</li>
 *   <li>MedioPago (2 filas) persistidos — no fallback de un solo pago</li>
 *   <li>XML generado pasa XSD v4.4 y CodigoActividadReceptor está a nivel ROOT</li>
 *   <li>Factura básica sin campos nuevos → XML byte-estable (no emite elementos vacíos)</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
class FacturaElectronicaFullFieldsIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // DB_USERNAME/DB_PASSWORD: override the stubs from application.properties with real TC values
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private EmpresaRepository empresaRepository;
    @Autowired private ClienteRepository clienteRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private ContadorConsecutivoRepository contadorConsecutivoRepository;
    @Autowired private LineaCodigoComercialRepository lineaCodigoComercialRepository;
    @Autowired private LineaDescuentoRepository lineaDescuentoRepository;
    @Autowired private ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    @Autowired private FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    @Autowired private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    @Autowired private FacturaMedioPagoRepository facturaMedioPagoRepository;
    @Autowired private FacturaService facturaService;
    @Autowired private XmlFacturaGeneratorService xmlFacturaGeneratorService;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("IT Full Fields");
        usuario.setEmail("it-full-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);
        usuarioId = usuario.getId();

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa IT Full Fields S.A.");
        nueva.setNumeroIdentificacion(
                String.valueOf(100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setCodigoActividad("620200");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("Dirección de prueba para IT full fields");
        nueva.setEmail("empresa-it@fractall.test");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        contadorConsecutivoRepository.save(new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));
        autenticarComo(usuarioId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(UUID usuarioId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuarioId, null, List.of()));
    }

    private Cliente crearCliente() {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente IT Full Fields");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310099" + System.nanoTime() % 1_000_000);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return clienteRepository.save(cliente);
    }

    private Producto crearProducto() {
        Producto producto = new Producto();
        producto.setCodigo("PROD-IT-" + UUID.randomUUID());
        producto.setDescripcion("Servicio de consultoría IT");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Sp");
        producto.setPrecioVenta(new BigDecimal("10000.00000"));
        producto.setGravado(true);
        producto.setPorcentajeImpuesto(new BigDecimal("13.00"));
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        return productoRepository.save(producto);
    }

    @Test
    void facturaConTodosLosCamposNuevosPersisteTodoYXmlEsValido() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto();

        // Linea con 2 codigos comerciales, 1 descuento, exoneracion inline, tipoTransaccion
        List<CodigoComercialRequest> codigos = List.of(
                new CodigoComercialRequest("01", "COD-INTERNO-001"),
                new CodigoComercialRequest("02", "COD-PROVEEDOR-ABC"));

        List<DescuentoRequest> descuentos = List.of(
                new DescuentoRequest(new BigDecimal("500.00000"), "01", null, "Descuento por volumen"));

        ExoneracionRequest exoneracionInline = new ExoneracionRequest(
                "08", null, "EXO-" + UUID.randomUUID().toString().substring(0, 8),
                null, null,
                "01", null,
                LocalDate.now().minusDays(10),
                new BigDecimal("13.00"),
                new BigDecimal("1235.00000"));

        LineaFacturaItemRequest linea = new LineaFacturaItemRequest(
                producto.getId(),
                new BigDecimal("1"),
                new BigDecimal("10000.00000"),
                null,                  // sin exoneracionId legacy
                codigos,
                descuentos,
                "01",                  // tipoTransaccion
                "UNIDAD-COM",          // unidadMedidaComercial
                new BigDecimal("0.00000"),  // ivaCobradoFabrica
                null,                  // factorCalculoIva
                exoneracionInline);

        List<OtrosCargoRequest> otrosCargos = List.of(
                new OtrosCargoRequest(
                        "01", null,
                        new IdentificacionTerceroRequest("01", "123456789"),
                        "Proveedor Cargo 1",
                        "Cargo por servicio de transporte",
                        null,
                        new BigDecimal("2000.00000")),
                new OtrosCargoRequest(
                        "02", null,
                        null,
                        null,
                        "Cargo adicional de manejo",
                        null,
                        new BigDecimal("1500.00000")));

        List<ReferenciaRequest> referencias = List.of(
                new ReferenciaRequest(
                        "01", null,
                        "FE-001-2024",
                        LocalDate.now().minusDays(30),
                        "01", null,
                        "Referencia a factura original"),
                new ReferenciaRequest(
                        "04", null,
                        null,
                        LocalDate.now().minusDays(5),
                        null, null, null));

        List<MedioPagoRequest> mediosPago = List.of(
                new MedioPagoRequest("01", null, new BigDecimal("5000.00000")),
                new MedioPagoRequest("02", null, new BigDecimal("8235.00000")));

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(),
                "99",                         // condicionVenta = Otros
                null,                          // plazoCredito
                "Pago diferido acordado",      // condicionVentaOtros
                "472000",                      // codigoActividadReceptor
                new BigDecimal("100.00000"),   // totalIvaDevuelto
                null,                          // medioPago legacy (deprecated)
                "CRC",
                new BigDecimal("1.00000"),
                List.of(linea),
                mediosPago,
                otrosCargos,
                referencias);

        FacturaResponse response = facturaService.crear(request);

        // --- Factura response assertions ---
        assertThat(response).isNotNull();
        assertThat(response.condicionVentaOtros()).isEqualTo("Pago diferido acordado");
        assertThat(response.codigoActividadReceptor()).isEqualTo("472000");
        assertThat(response.totalIvaDevuelto()).isEqualByComparingTo(new BigDecimal("100.00000"));

        // --- Line assertions ---
        assertThat(response.lineas()).hasSize(1);
        var lineaResp = response.lineas().get(0);
        // subtotal = 10000 - 500 (descuento) = 9500
        assertThat(lineaResp.subtotal()).isEqualByComparingTo(new BigDecimal("9500.00000"));

        // --- Child table rows in DB: CodigosComerciales ---
        UUID lineaId = lineaResp.id();
        List<LineaCodigoComercial> codigosGuardados =
                lineaCodigoComercialRepository.findByLineaIdOrderByOrden(lineaId);
        assertThat(codigosGuardados).hasSize(2);
        assertThat(codigosGuardados.get(0).getTipo()).isEqualTo("01");
        assertThat(codigosGuardados.get(0).getCodigo()).isEqualTo("COD-INTERNO-001");
        assertThat(codigosGuardados.get(1).getTipo()).isEqualTo("02");
        assertThat(codigosGuardados.get(1).getOrden()).isEqualTo((short) 2);

        // --- Child table rows in DB: Descuentos ---
        List<LineaDescuento> descuentosGuardados =
                lineaDescuentoRepository.findByLineaIdOrderByOrden(lineaId);
        assertThat(descuentosGuardados).hasSize(1);
        assertThat(descuentosGuardados.get(0).getMontoDescuento())
                .isEqualByComparingTo(new BigDecimal("500.00000"));
        assertThat(descuentosGuardados.get(0).getNaturalezaDescuento()).isEqualTo("Descuento por volumen");

        // --- Child table rows in DB: ImpuestoLineaExoneracion ---
        ImpuestoLineaExoneracion exGuardada =
                impuestoLineaExoneracionRepository.findByLineaId(lineaId).orElse(null);
        assertThat(exGuardada).isNotNull();
        assertThat(exGuardada.getTipoDocumentoEx1()).isEqualTo("08");
        assertThat(exGuardada.getMontoExoneracion()).isEqualByComparingTo(new BigDecimal("1235.00000"));

        // --- Child table rows in DB: OtrosCargos ---
        UUID facturaId = response.id();
        List<FacturaOtrosCargos> otrosCargosGuardados =
                facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(facturaId);
        assertThat(otrosCargosGuardados).hasSize(2);
        assertThat(otrosCargosGuardados.get(0).getOrden()).isEqualTo((short) 1);
        assertThat(otrosCargosGuardados.get(0).getMontoCargo())
                .isEqualByComparingTo(new BigDecimal("2000.00000"));
        assertThat(otrosCargosGuardados.get(1).getOrden()).isEqualTo((short) 2);
        assertThat(otrosCargosGuardados.get(1).getMontoCargo())
                .isEqualByComparingTo(new BigDecimal("1500.00000"));

        // --- Child table rows in DB: InformacionReferencia ---
        List<FacturaInformacionReferencia> referenciasGuardadas =
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(facturaId);
        assertThat(referenciasGuardadas).hasSize(2);
        assertThat(referenciasGuardadas.get(0).getOrden()).isEqualTo((short) 1);
        assertThat(referenciasGuardadas.get(0).getNumero()).isEqualTo("FE-001-2024");
        assertThat(referenciasGuardadas.get(1).getOrden()).isEqualTo((short) 2);

        // --- Child table rows in DB: MedioPago ---
        List<FacturaMedioPago> mediosPagoGuardados =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(facturaId);
        assertThat(mediosPagoGuardados).hasSize(2);
        assertThat(mediosPagoGuardados.get(0).getTipoMedioPago()).isEqualTo("01");
        assertThat(mediosPagoGuardados.get(0).getTotalMedioPago())
                .isEqualByComparingTo(new BigDecimal("5000.00000"));
        assertThat(mediosPagoGuardados.get(1).getTipoMedioPago()).isEqualTo("02");

        // --- XML generation + XSD validation + structural assertions ---
        // response.comprobanteId() is the UUID of the ComprobanteElectronico saved by FacturaService
        String xml = xmlFacturaGeneratorService.generarXmlFactura(response.comprobanteId());
        assertThat(xml).isNotBlank();

        // CodigoActividadReceptor at ROOT level (after CodigoActividadEmisor)
        int idxEmisor = xml.indexOf("<CodigoActividadEmisor>");
        int idxReceptorBlock = xml.indexOf("<CodigoActividadReceptor>");
        assertThat(idxReceptorBlock).as("CodigoActividadReceptor debe aparecer en el XML").isGreaterThan(0);
        assertThat(idxReceptorBlock).as("CodigoActividadReceptor debe aparecer DESPUÉS de CodigoActividadEmisor")
                .isGreaterThan(idxEmisor);
        // Must be before <NumeroConsecutivo> (still at root level)
        int idxConsecutivo = xml.indexOf("<NumeroConsecutivo>");
        assertThat(idxReceptorBlock).as("CodigoActividadReceptor debe aparecer ANTES de NumeroConsecutivo")
                .isLessThan(idxConsecutivo);
        // Must NOT be inside <Receptor> block
        int idxReceptorStart = xml.indexOf("<Receptor>");
        int idxReceptorEnd = xml.indexOf("</Receptor>");
        assertThat(idxReceptorBlock < idxReceptorStart || idxReceptorBlock > idxReceptorEnd)
                .as("CodigoActividadReceptor NO debe estar dentro del bloque <Receptor>")
                .isTrue();

        // OtrosCargos in XML
        assertThat(xml).contains("<OtrosCargos>");
        // Two occurrences for two cargo entries
        int ocCount = countOccurrences(xml, "<OtrosCargos>");
        assertThat(ocCount).isEqualTo(2);

        // InformacionReferencia in XML — after </ResumenFactura>
        assertThat(xml).contains("<InformacionReferencia>");
        int idxResumenEnd = xml.indexOf("</ResumenFactura>");
        int idxRefFirst = xml.indexOf("<InformacionReferencia>");
        assertThat(idxRefFirst).as("InformacionReferencia debe aparecer después de </ResumenFactura>")
                .isGreaterThan(idxResumenEnd);

        // MedioPago — 2 entries in ResumenFactura
        int mpCount = countOccurrences(xml, "<MedioPago>");
        assertThat(mpCount).isEqualTo(2);

        // CodigosComerciales in XML
        assertThat(xml).contains("<CodigoComercial>");
        int ccCount = countOccurrences(xml, "<CodigoComercial>");
        assertThat(ccCount).isEqualTo(2);

        // Descuentos in XML
        assertThat(xml).contains("<Descuento>");

        // CondicionVentaOtros in XML
        assertThat(xml).contains("<CondicionVentaOtros>Pago diferido acordado</CondicionVentaOtros>");

        // XSD validation — xmlFacturaGeneratorService.generarXmlFactura already calls XSD validator
        // If we got here without exception, XSD passed.
    }

    @Test
    void facturaBasicaSinCamposNuevosNoEmiteElementosVaciosEnXml() {
        Cliente cliente = crearCliente();
        Producto producto = crearProducto();

        LineaFacturaItemRequest linea = new LineaFacturaItemRequest(
                producto.getId(),
                new BigDecimal("1"),
                new BigDecimal("5000.00000"),
                null, null, null, null, null, null, null, null);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(),
                null, null, null, null, null,
                null, null, null,
                List.of(linea),
                null, null, null);

        FacturaResponse response = facturaService.crear(request);
        UUID facturaId = response.id();

        String xml = xmlFacturaGeneratorService.generarXmlFactura(response.comprobanteId());
        assertThat(xml).isNotBlank();

        // No new-field elements emitted when absent
        assertThat(xml).doesNotContain("<CodigoActividadReceptor>");
        assertThat(xml).doesNotContain("<CondicionVentaOtros>");
        assertThat(xml).doesNotContain("<OtrosCargos>");
        assertThat(xml).doesNotContain("<InformacionReferencia>");
        assertThat(xml).doesNotContain("<CodigoComercial>");
        assertThat(xml).doesNotContain("<Descuento>");

        // Single MedioPago emitted (legacy fallback)
        int mpCount = countOccurrences(xml, "<MedioPago>");
        assertThat(mpCount).isEqualTo(1);

        // XSD validation passed (no exception thrown above)
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(fragment, idx)) != -1) {
            count++;
            idx += fragment.length();
        }
        return count;
    }
}
