package cr.ac.fractall.reportes.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaNotaCreditoRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.facturacion.servicio.NotaCreditoDebitoService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de integración de punta a punta de {@code GET /reportes/iva} (Release 3 / Fase D, PR4,
 * ver el diseño) -- la PRIMERA prueba real del theta-join JPQL de {@code ReporteIvaRepository} y
 * del filtrado automático por {@code @TenantId} contra Postgres real: {@code ReporteIvaServiceTest}
 * (PR3) solo mockeó ambos repositorios, así que ninguna de las dos piezas se había ejercitado
 * nunca contra datos reales antes de esta clase.
 *
 * <p>Mismo bootstrap mínimo (solo Postgres, sin Vault) que
 * {@code CalculadoraImpuestoLineaReconciliacionIT}: las facturas y NC/ND de este PR se crean
 * invocando {@link FacturaService#crear}/{@link NotaCreditoDebitoService#crearNotaCredito}/
 * {@link NotaCreditoDebitoService#crearNotaDebito} directamente (nunca vía HTTP {@code POST
 * /facturas}), porque ninguno de esos métodos de servicio toca Vault/XML/Hacienda -- solo
 * {@code ComprobanteXmlPersistenceService#generarYPersistirXml} (invocado por
 * {@code FacturaController}, no por el servicio) hace eso. El único tramo HTTP real ejercitado
 * aquí es el propio {@code GET /reportes/iva} bajo prueba, con un JWT real emitido por
 * {@link JwtService}.
 *
 * <p>{@code estado}/{@code fechaEmision} de cada comprobante se fuerzan directamente vía
 * {@link ComprobanteElectronicoRepository} después de crear cada fixture -- mismo atajo que
 * {@code FacturaControllerTest#crearFacturaAPlazoAceptada} usa para {@code estado} sola, extendido
 * acá a {@code fechaEmision} porque el diseño exige traversal por el período de emisión PROPIO del
 * comprobante (nunca el de {@code create_date}, que sería "hoy" para cualquier fixture de prueba).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReporteIvaIT {

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
    @Autowired private NotaCreditoDebitoService notaCreditoDebitoService;
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
        usuario.setNombre("IT Reporte IVA");
        usuario.setEmail("it-reporte-iva-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa IT Reporte IVA S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setCodigoActividad("620200");
        nueva.setCodigoProvincia("1");
        nueva.setCanton("01");
        nueva.setDistrito("01");
        nueva.setOtrasSenas("Dirección de prueba IT reporte IVA");
        nueva.setEmail("empresa-it-reporte-iva-" + UUID.randomUUID() + "@fractall.test");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuarioId);
        nueva.setCreateDate(ahora);
        nueva.setUpdateDate(ahora);
        Empresa empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        contadorConsecutivoRepository.save(new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));

        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente IT Reporte IVA");
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion("310099" + System.nanoTime() % 1_000_000L);
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
        producto.setCodigo("PROD-IVA-IT-" + UUID.randomUUID());
        producto.setDescripcion("Producto de prueba reporte IVA");
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

    private FacturaResponse crearFactura(ContextoTenant ctx, Producto producto, BigDecimal precioUnitario) {
        return comoTenant(ctx, () -> facturaService.crear(new CrearFacturaRequest(
                ctx.clienteId(), null, null, null, null, null, null, null, null,
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

    // ─────────────────────────────────────────────────────────────────────────
    // Aislamiento por tenant
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prueba real del {@code @TenantId} automático de {@code ReporteIvaRepository} (theta-join
     * JPQL de 3 entidades, decisión A3 del diseño) -- una factura ACEPTADA del tenant A, dentro del
     * período consultado, nunca debe aparecer en el reporte del tenant B.
     */
    @Test
    void facturaDeOtroTenantNoApareceEnElReporte() throws Exception {
        ContextoTenant tenantA = crearTenant();
        ContextoTenant tenantB = crearTenant();

        Producto productoA = comoTenant(tenantA, () -> crearProducto(true, new BigDecimal("13.00")));
        FacturaResponse facturaA = crearFactura(tenantA, productoA, new BigDecimal("1000.00000"));
        forzarEstadoYFecha(tenantA, facturaA.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 15, 10, 0));

        mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenantB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle").isArray())
                .andExpect(jsonPath("$.detalle.length()").value(0))
                .andExpect(jsonPath("$.resumen.length()").value(0))
                .andExpect(jsonPath("$.totalDebitoFiscal").value(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RECHAZADO nunca contribuye
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prueba real del filtro {@code c.estado = 'ACEPTADO'} de la consulta JPQL -- un comprobante
     * RECHAZADO fechado dentro del período no debe generar ninguna fila de detalle ni aportar al
     * total.
     */
    @Test
    void comprobanteRechazadoNoContribuyeAlReporte() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));
        FacturaResponse factura = crearFactura(tenant, producto, new BigDecimal("1000.00000"));
        forzarEstadoYFecha(tenant, factura.comprobanteId(), "RECHAZADO",
                LocalDateTime.of(2026, 8, 20, 9, 0));

        mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.length()").value(0))
                .andExpect(jsonPath("$.totalDebitoFiscal").value(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NC de septiembre contra factura de julio -- atribución por período PROPIO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * El caso central del diseño (resolved decision 3, requisito "Signed Traversal by Own Issue
     * Period"): una NC fechada en septiembre que referencia una factura de julio debe afectar
     * SOLO el reporte de septiembre -- el reporte de julio no debe verse afectado por ella. Esto
     * demuestra que la atribución es por {@code fecha_emision} PROPIA del comprobante, nunca la del
     * comprobante referenciado.
     */
    @Test
    void notaCreditoDeSeptiembreContraFacturaDeJulioAfectaSeptiembreNoJulio() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));

        FacturaResponse facturaJulio = crearFactura(tenant, producto, new BigDecimal("1000.00000"));
        forzarEstadoYFecha(tenant, facturaJulio.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 7, 15, 10, 0));

        UUID lineaOrigenId = facturaJulio.lineas().get(0).id();
        FacturaResponse notaCredito = comoTenant(tenant, () -> notaCreditoDebitoService.crearNotaCredito(
                new CrearNotaCreditoRequest(facturaJulio.id(), "02", null, "Corrección total (IT reporte IVA)",
                        List.of(new LineaNotaCreditoRequest(lineaOrigenId, BigDecimal.ONE)))));
        forzarEstadoYFecha(tenant, notaCredito.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 9, 10, 11, 0));

        // Julio: solo la factura -- 1000.00000 * 13% = 130.00000 de débito fiscal.
        mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-07-01")
                        .param("hasta", "2026-07-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.length()").value(1))
                .andExpect(jsonPath("$.detalle[0].tipoComprobante").value("01"))
                .andExpect(jsonPath("$.totalDebitoFiscal").value(130.0));

        // Septiembre: solo la NC -- signo -1, débito fiscal -130.00000.
        mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-09-01")
                        .param("hasta", "2026-09-30")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.length()").value(1))
                .andExpect(jsonPath("$.detalle[0].tipoComprobante").value("03"))
                .andExpect(jsonPath("$.detalle[0].signo").value(-1))
                .andExpect(jsonPath("$.totalDebitoFiscal").value(-130.0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ND aceptada aumenta su propia tarifa
    // ─────────────────────────────────────────────────────────────────────────

    /** Requisito "Accepted ND increases its own tarifa": signo {@code +1}, misma tarifa 13%. */
    @Test
    void notaDebitoAceptadaAumentaSuPropiaTarifa() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));

        FacturaResponse factura = crearFactura(tenant, producto, new BigDecimal("1000.00000"));
        forzarEstadoYFecha(tenant, factura.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 5, 9, 0));

        Producto productoNd = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));
        FacturaResponse notaDebito = comoTenant(tenant, () -> notaCreditoDebitoService.crearNotaDebito(
                new CrearNotaDebitoRequest(factura.id(), "05", null, "Cargo adicional (IT reporte IVA)",
                        List.of(new LineaFacturaItemRequest(productoNd.getId(), BigDecimal.ONE, new BigDecimal("500.00000"),
                                null, null, null, null, null, null, null, null)))));
        forzarEstadoYFecha(tenant, notaDebito.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 12, 14, 0));

        // factura: 1000.00000 * 13% = 130.00000 ; ND: 500.00000 * 13% = 65.00000 ; tarifa 13% = 195.00000.
        mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.length()").value(2))
                .andExpect(jsonPath("$.resumen.length()").value(1))
                .andExpect(jsonPath("$.resumen[0].porcentajeImpuesto").value(13.0))
                .andExpect(jsonPath("$.resumen[0].impuestoNeto").value(195.0))
                .andExpect(jsonPath("$.totalDebitoFiscal").value(195.0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sin medioPago en ningún nivel del cuerpo crudo
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Requisito "No medio_pago Field": se asegura sobre el STRING crudo del cuerpo de la
     * respuesta, no sobre la forma del DTO -- una futura adición accidental de un campo
     * {@code medioPago} en cualquier nivel (resumen, detalle, o la raíz) haría fallar esta
     * aserción sin importar dónde se agregue.
     */
    @Test
    void respuestaJsonNoContieneMedioPagoEnNingunNivel() throws Exception {
        ContextoTenant tenant = crearTenant();
        Producto producto = comoTenant(tenant, () -> crearProducto(true, new BigDecimal("13.00")));
        FacturaResponse factura = crearFactura(tenant, producto, new BigDecimal("1000.00000"));
        forzarEstadoYFecha(tenant, factura.comprobanteId(), "ACEPTADO",
                LocalDateTime.of(2026, 8, 3, 8, 0));

        String cuerpo = mockMvc.perform(get("/reportes/iva")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", "Bearer " + tenant.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).isNotBlank();
        assertThat(cuerpo).doesNotContainIgnoringCase("medioPago");
    }
}
