package cr.ac.fractall.reportes.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CobroRegistradoResponse;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.RegistrarCobroRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.facturacion.servicio.CobroFacturaService;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.reportes.export.ReporteFlujoCajaExcelWriter;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración de punta a punta de {@code GET /reportes/flujo-caja} (Release 3 / Fase D,
 * Change 2 de 2, PR5 -- ver el diseño obs #918 y {@code sdd/reporte-flujo-caja/tasks}, Fase 5).
 * Primera prueba real de {@code ReporteFlujoCajaController}/{@code ReporteFlujoCajaService} contra
 * Postgres real bajo un JWT real: {@code ReporteFlujoCajaServiceTest} (PR4) mockeó el repositorio
 * por completo, y {@code ReporteFlujoCajaRepositoryIT} (PR2/PR3) nunca pasó por HTTP.
 *
 * <p>Mismo bootstrap mínimo (solo Postgres, sin Vault) que {@code ReporteIvaIT}: las facturas de
 * este PR se crean invocando {@link FacturaService#crear} y los cobros invocando
 * {@link CobroFacturaService#registrar} directamente (nunca vía HTTP {@code POST /facturas} o
 * {@code POST /facturas/{id}/cobros}), porque ninguno de esos métodos de servicio toca Vault/XML/
 * Hacienda. El único tramo HTTP real ejercitado aquí es el propio {@code GET /reportes/flujo-caja}
 * bajo prueba, con un JWT real emitido por {@link JwtService}.
 *
 * <p>{@code estado}/{@code fechaEmision} de cada comprobante se fuerzan directamente vía
 * {@link ComprobanteElectronicoRepository} después de crear cada fixture -- mismo atajo que
 * {@code ReporteIvaIT#forzarEstadoYFecha}, porque el diseño exige traversal por el período de
 * emisión PROPIO del comprobante. {@code fechaCobro} de {@code cobro_factura}, en cambio, es un
 * campo real de {@link RegistrarCobroRequest} -- no requiere forzado posterior.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReporteFlujoCajaIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private EmpresaRepository empresaRepository;
    @Autowired private ClienteRepository clienteRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private ContadorConsecutivoRepository contadorConsecutivoRepository;
    @Autowired private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    @Autowired private FacturaService facturaService;
    @Autowired private CobroFacturaService cobroFacturaService;
    @Autowired private JwtService jwtService;

    private record ContextoTenant(UUID empresaId, UUID usuarioId, UUID clienteId, String accessToken) {
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * Crea un tenant completo (usuario + empresa + cliente + contador de consecutivo) y su JWT --
     * TenantContext/SecurityContextHolder quedan limpios al retornar; cada fixture posterior los
     * vuelve a fijar puntualmente vía {@link #comoTenant}.
     */
    private ContextoTenant crearTenant() {
        LocalDateTime ahora = LocalDateTime.now();
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("IT Reporte Flujo Caja");
        usuario.setEmail("it-reporte-flujo-caja-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(ahora);
        usuario.setUpdateDate(ahora);
        usuario = usuarioRepository.save(usuario);
        UUID usuarioId = usuario.getId();

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa IT Reporte Flujo Caja S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setCodigoActividad("620200");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("Dirección de prueba IT reporte flujo de caja");
        nueva.setEmail("empresa-it-reporte-flujo-caja-" + UUID.randomUUID() + "@fractall.test");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(ahora);
        nueva.setUpdateDate(ahora);
        Empresa empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        contadorConsecutivoRepository.save(new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));

        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente IT Reporte Flujo Caja");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310098" + System.nanoTime() % 1_000_000L);
        cliente.setRequiereFacturaElectronica(true);
        cliente.setCreateDate(ahora);
        cliente.setUpdateDate(ahora);
        cliente = clienteRepository.save(cliente);

        String accessToken = jwtService.generarToken(usuarioId, empresa.getId());

        TenantContext.clear();

        return new ContextoTenant(empresa.getId(), usuarioId, cliente.getId(), accessToken);
    }

    /**
     * Ejecuta {@code accion} con {@code TenantContext}/{@code SecurityContextHolder} fijados al
     * tenant indicado, limpiando ambos al terminar -- así el JWT real usado en las llamadas MockMvc
     * (resuelto por {@code JwtTenantFilter} por request) nunca queda "contaminado" por el contexto
     * usado para construir los fixtures directamente vía servicio.
     */
    private <T> T comoTenant(ContextoTenant ctx, Supplier<T> accion) {
        TenantContext.set(ctx.empresaId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ctx.usuarioId(), null, List.of()));
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private Producto crearProducto(boolean gravado, BigDecimal porcentajeImpuesto) {
        LocalDateTime ahora = LocalDateTime.now();
        Producto producto = new Producto();
        producto.setCodigo("PROD-FC-IT-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba reporte flujo de caja");
        producto.setCodigoCabys("2132100000100");
        producto.setDescripcionCabys("Descripción CABYS de prueba");
        producto.setCabysValidadoEn(ahora);
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(gravado);
        producto.setPorcentajeImpuesto(porcentajeImpuesto);
        producto.setActivo(true);
        producto.setCreateDate(ahora);
        producto.setUpdateDate(ahora);
        return productoRepository.save(producto);
    }

    /**
     * Crea la factura con {@code condicionVenta}/{@code medioPago} explícitos -- a diferencia de
     * {@code ReporteIvaIT#crearFactura}, aquí ambos importan: {@code condicionVenta} determina si
     * la factura es cobrable ({@link CobroFacturaService#registrar} exige {@code IN ('02','03','04')})
     * y {@code medioPago} es el valor de {@code factura.medio_pago} que la prueba
     * {@code respuestaUsaMedioPagoDelCobroNoElDeLaFactura} debe probar que NUNCA llega a la
     * respuesta (Requisito "Cobros Series Groups by cobro_factura.medio_pago Only").
     */
    private FacturaResponse crearFactura(
            ContextoTenant ctx, Producto producto, BigDecimal precioUnitario,
            String condicionVenta, Integer plazoCredito, String medioPago) {
        return comoTenant(ctx, () -> facturaService.crear(new CrearFacturaRequest(
                ctx.clienteId(), condicionVenta, plazoCredito, null, null, null, medioPago, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, precioUnitario,
                        null, null, null, null, null, null, null, null)),
                null, null, null)));
    }

    /** Fuerza {@code estado}/{@code fechaEmision} del comprobante -- ver el javadoc de la clase. */
    private void forzarEstadoYFecha(ContextoTenant ctx, UUID comprobanteId, String estado, LocalDateTime fechaEmision) {
        comoTenant(ctx, () -> {
            ComprobanteElectronico comprobante =
                    comprobanteElectronicoRepository.findById(comprobanteId).orElseThrow();
            comprobante.setEstado(estado);
            comprobante.setFechaEmision(fechaEmision);
            return comprobanteElectronicoRepository.save(comprobante);
        });
    }

    /** Registra un cobro real vía {@link CobroFacturaService#registrar} -- {@code fechaCobro} es un campo real, no requiere forzado posterior. */
    private CobroRegistradoResponse registrarCobro(
            ContextoTenant ctx, UUID facturaId, BigDecimal montoCobrado, String medioPago, LocalDateTime fechaCobro) {
        return comoTenant(ctx, () -> cobroFacturaService.registrar(
                facturaId, new RegistrarCobroRequest(montoCobrado, medioPago, null, fechaCobro)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // El caso central del cambio: nunca sumar ventas y cobros
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * El caso central del diseño (Requisito "Ventas Series Includes All condicion_venta Values" +
     * "Cobros Series Groups by cobro_factura.medio_pago Only"): una factura emitida en julio y
     * cobrada en agosto debe aparecer en la serie de VENTAS de julio (por {@code fecha_emision}
     * PROPIA) y en la serie de COBROS de agosto (por {@code fecha_cobro} del {@code cobro_factura})
     * -- NUNCA sumadas en el mismo período, ni contadas dos veces en ninguno de los dos.
     */
    @Test
    void facturaDeJulioCobradaEnAgostoApareceEnVentasDeJulioYCobrosDeAgosto() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));

        FacturaResponse factura = crearFactura(
                tenant, producto, new BigDecimal("1000.00000"), "02", 30, "01");
        forzarEstadoYFecha(tenant, factura.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 7, 15, 10, 0));

        registrarCobro(tenant, factura.id(), new BigDecimal("500.00000"), "04",
                LocalDateTime.of(2026, 8, 10, 9, 0));

        // Julio: solo la venta -- 1000.00000 * 1.13 = 1130.00000 ; sin cobros.
        mockMvc.perform(get("/reportes/flujo-caja")
                        .param("desde", "2026-07-01")
                        .param("hasta", "2026-07-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventas.total").value(1130.0))
                .andExpect(jsonPath("$.ventas.cantidadComprobantes").value(1))
                .andExpect(jsonPath("$.cobros.total").value(0))
                .andExpect(jsonPath("$.cobros.cantidadCobros").value(0));

        // Agosto: solo el cobro -- 500.00000 ; sin ventas.
        mockMvc.perform(get("/reportes/flujo-caja")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventas.total").value(0))
                .andExpect(jsonPath("$.ventas.cantidadComprobantes").value(0))
                .andExpect(jsonPath("$.cobros.total").value(500.0))
                .andExpect(jsonPath("$.cobros.cantidadCobros").value(1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aislamiento por tenant
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prueba real del {@code empresa_id} explícito de las 3 consultas nativas (Requisito "Tenant
     * Isolation on All Three Native Queries") a nivel de punta a punta: una factura y su cobro del
     * tenant A nunca deben aparecer en el reporte del tenant B, ni en ventas, ni en cobros, ni en
     * cartera.
     */
    @Test
    void facturaDeOtroTenantNoApareceEnElFlujoDeCaja() throws Exception {
        ContextoTenant tenantA = crearTenant();
        ContextoTenant tenantB = crearTenant();

        Producto productoA = comoTenant(tenantA, () -> crearProducto(true, new BigDecimal("13.00")));
        FacturaResponse facturaA = crearFactura(
                tenantA, productoA, new BigDecimal("1000.00000"), "02", 30, "01");
        forzarEstadoYFecha(tenantA, facturaA.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 5, 9, 0));
        registrarCobro(tenantA, facturaA.id(), new BigDecimal("300.00000"), "04",
                LocalDateTime.of(2026, 8, 20, 10, 0));

        mockMvc.perform(get("/reportes/flujo-caja")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenantB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventas.total").value(0))
                .andExpect(jsonPath("$.ventas.cantidadComprobantes").value(0))
                .andExpect(jsonPath("$.cobros.total").value(0))
                .andExpect(jsonPath("$.cobros.cantidadCobros").value(0))
                .andExpect(jsonPath("$.cartera.total").value(0))
                .andExpect(jsonPath("$.cartera.cantidadFacturas").value(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // El medio de pago del cobro, nunca el de la factura
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Requisito "Cobros Series Groups by cobro_factura.medio_pago Only": con
     * {@code factura.medio_pago = '01'} y {@code cobro_factura.medio_pago = '04'}, la respuesta
     * debe agrupar bajo {@code '04'} -- se asegura sobre el STRING crudo del cuerpo de la
     * respuesta (normalizado sin espacios), no sobre la forma del DTO, para que una futura fuga
     * accidental de {@code factura.medio_pago} en cualquier nivel haga fallar esta aserción sin
     * importar dónde se agregue.
     */
    @Test
    void respuestaUsaMedioPagoDelCobroNoElDeLaFactura() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));

        FacturaResponse factura = crearFactura(
                tenant, producto, new BigDecimal("1000.00000"), "02", 30, "01");
        forzarEstadoYFecha(tenant, factura.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 3, 8, 0));
        registrarCobro(tenant, factura.id(), new BigDecimal("400.00000"), "04",
                LocalDateTime.of(2026, 8, 12, 11, 0));

        String cuerpo = mockMvc.perform(get("/reportes/flujo-caja")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).isNotBlank();
        String normalizado = cuerpo.replaceAll("\\s+", "");
        assertThat(normalizado).contains("\"medioPago\":\"04\"");
        assertThat(normalizado).doesNotContain("\"medioPago\":\"01\"");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rango inválido -- 400 vía RangoFechasInvalidaException + GlobalExceptionHandler
    // ─────────────────────────────────────────────────────────────────────────

    /** {@code hasta} anterior a {@code desde} debe fallar con 400, reusando el handler existente. */
    @Test
    void rangoInvalidoDevuelveCuatrocientos() throws Exception {
        ContextoTenant tenant = crearTenant();

        mockMvc.perform(get("/reportes/flujo-caja")
                        .param("desde", "2026-08-31")
                        .param("hasta", "2026-08-01")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export Excel -- PR6 (ver sdd/reporte-flujo-caja/tasks, Fase 6)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code GET /reportes/flujo-caja/excel} debe responder con el
     * {@code Content-Disposition} de un archivo {@code .xlsx}, mismo criterio que
     * {@code ReporteIvaController#excel} -- delegación de una sola línea a
     * {@link ReporteFlujoCajaExcelWriter#generar}, sin try/catch.
     */
    @Test
    void excelDevuelveContentDispositionXlsx() throws Exception {
        ContextoTenant tenant = crearTenant();

        mockMvc.perform(get("/reportes/flujo-caja/excel")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        containsString(
                                "attachment; filename=\"reporte-flujo-caja_2026-08-01_2026-08-31.xlsx\"")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export PDF -- PR7 (ver sdd/reporte-flujo-caja/tasks, Fase 7)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code GET /reportes/flujo-caja/pdf} debe responder con el {@code Content-Disposition} de un
     * archivo {@code .pdf}, mismo criterio que {@code ReporteIvaController#pdf} -- delegación de
     * una sola línea a {@link cr.ac.fractall.reportes.export.ReporteFlujoCajaPdfWriter#generar},
     * sin try/catch.
     */
    @Test
    void pdfDevuelveContentDispositionPdf() throws Exception {
        ContextoTenant tenant = crearTenant();

        mockMvc.perform(get("/reportes/flujo-caja/pdf")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        containsString(
                                "attachment; filename=\"reporte-flujo-caja_2026-08-01_2026-08-31.pdf\"")));
    }
}
