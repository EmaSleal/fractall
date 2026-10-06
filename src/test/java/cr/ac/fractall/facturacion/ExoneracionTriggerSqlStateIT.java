package cr.ac.fractall.facturacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * T-001 (criterio 4): reproduce contra PostgreSQL real los 3 casos de {@code
 * fn_validar_exoneracion_vigente} (V10, redefinida por V25__exoneracion_sqlstate_propio.sql) que
 * {@link cr.ac.fractall.facturacion.servicio.LineaFacturaEnsamblador#ensamblar} ya rechaza en Java
 * ANTES de persistir -- aquí se salta esa validación insertando la {@code linea_factura}
 * directamente vía {@link LineaFacturaRepository}, igual que haría la carrera real descrita en la
 * spec de T-001 (la exoneración se desactiva/vence entre la validación Java y el flush), para que
 * el trigger sea quien rechace y se pueda verificar que {@code GlobalExceptionHandler} traduce
 * cada SQLSTATE propio ({@code VX001}/{@code VX002}/{@code VX003}) a 400 con un mensaje de dominio
 * limpio -- nunca el texto crudo de Postgres ("ERROR:", "Where:", nombre de la función PL/pgSQL).
 *
 * <p>El endpoint {@code POST /test/linea-factura-directa} no existe en producción: es un
 * {@code @RestController} de prueba registrado solo en el contexto de este test vía
 * {@code @Import}, para que la petición pase por el {@code GlobalExceptionHandler} real de la
 * aplicación (no un stub) y la respuesta HTTP se pueda verificar end-to-end.
 *
 * <p><b>T-001 (corrección, criterio 7):</b> {@link #lineaFacturaConProductoDeOtroTenant_devuelve409ConMensajeGenericoSinFugaDeDatos}
 * reutiliza la misma infraestructura para reproducir, contra el mismo endpoint y el mismo
 * {@code POST /test/linea-factura-directa}, la regresión de seguridad detectada en revisión:
 * {@code fn_validar_mismo_tenant} (V10/V18/V19/V23) también lanza su {@code RAISE EXCEPTION} sin
 * SQLSTATE propio ({@code P0001}), pero a diferencia de {@code fn_validar_exoneracion_vigente}
 * SÍ incluye el {@code empresa_id} de otro tenant en el texto del error -- el fallback genérico de
 * {@code GlobalExceptionHandler} nunca debe filtrar ese texto crudo en la respuesta HTTP.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExoneracionTriggerSqlStateIT.ControladorDeLineaFacturaDirecta.class)
class ExoneracionTriggerSqlStateIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Inserta una {@code linea_factura} directamente vía repositorio -- sin pasar por {@code
     * LineaFacturaEnsamblador}, que rechazaría estos 3 casos en Java antes de llegar aquí. Simula
     * el estado que vería el trigger en la ventana de carrera real.
     */
    @RestController
    static class ControladorDeLineaFacturaDirecta {

        private final LineaFacturaRepository lineaFacturaRepository;

        ControladorDeLineaFacturaDirecta(LineaFacturaRepository lineaFacturaRepository) {
            this.lineaFacturaRepository = lineaFacturaRepository;
        }

        record LineaFacturaDirectaRequest(UUID facturaId, UUID productoId, UUID exoneracionId) {
        }

        @PostMapping("/test/linea-factura-directa")
        public void crear(@RequestBody LineaFacturaDirectaRequest request) {
            LineaFactura linea = new LineaFactura();
            linea.setFacturaId(request.facturaId());
            linea.setProductoId(request.productoId());
            linea.setNumeroLinea(1);
            linea.setCantidad(BigDecimal.ONE);
            linea.setPrecioUnitario(new BigDecimal("1000.00000"));
            linea.setSubtotal(new BigDecimal("1000.00000"));
            linea.setCodigoCabysAplicado("2132100000100");
            linea.setGravadoAplicado(true);
            linea.setPorcentajeImpuestoAplicado(new BigDecimal("13.00"));
            linea.setTipoTransaccion("01");
            linea.setIvaCobradoFabrica(BigDecimal.ZERO);
            linea.setExoneracionId(request.exoneracionId());
            linea.setPorcentajeExoneracionAplicado(new BigDecimal("100.00"));
            linea.setMontoExoneracionAplicado(new BigDecimal("130.00000"));
            lineaFacturaRepository.saveAndFlush(linea);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private EmpresaRepository empresaRepository;
    @Autowired private ClienteRepository clienteRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private ClienteExoneracionRepository clienteExoneracionRepository;
    @Autowired private FacturaRepository facturaRepository;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID usuarioId;
    private Cliente cliente;
    private Producto producto;
    private Factura factura;
    private String accessToken;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("IT Exoneracion SQLSTATE");
        usuario.setEmail("it-exoneracion-sqlstate-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);
        usuarioId = usuario.getId();

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa IT Exoneracion SQLSTATE S.A.");
        empresa.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(usuarioId);
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(empresa);

        TenantContext.set(empresa.getId());

        cliente = crearCliente();
        producto = crearProducto();
        factura = crearFacturaMinima(cliente.getId());
        accessToken = jwtService.generarToken(usuarioId, empresa.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Cliente crearCliente() {
        Cliente c = new Cliente();
        c.setNombre("Cliente IT Exoneracion SQLSTATE");
        c.setTipoIdentificacion("02");
        c.setNumeroIdentificacion("310" + System.nanoTime() % 1_000_000_000L);
        c.setRequiereFacturaElectronica(true);
        c.setCreateDate(LocalDateTime.now());
        c.setUpdateDate(LocalDateTime.now());
        return clienteRepository.save(c);
    }

    private Producto crearProducto() {
        Producto p = new Producto();
        p.setCodigo("PROD-EXO-" + UUID.randomUUID());
        p.setDescripcion("Producto de prueba");
        p.setCodigoCabys("2132100000100");
        p.setDescripcionCabys("Descripción CABYS de prueba");
        p.setCabysValidadoEn(LocalDateTime.now());
        p.setCodigoUnidadFe("Unid");
        p.setPrecioVenta(new BigDecimal("1000.00000"));
        p.setGravado(true);
        p.setPorcentajeImpuesto(new BigDecimal("13.00"));
        p.setActivo(true);
        p.setCreateDate(LocalDateTime.now());
        p.setUpdateDate(LocalDateTime.now());
        return productoRepository.save(p);
    }

    private Factura crearFacturaMinima(UUID clienteId) {
        Factura f = new Factura();
        f.setClienteId(clienteId);
        f.setCondicionVenta("01");
        f.setMedioPago("01");
        f.setMoneda("CRC");
        f.setTipoCambio(new BigDecimal("1.00000"));
        f.setSubtotal(BigDecimal.ZERO);
        f.setTotalImpuesto(BigDecimal.ZERO);
        f.setTotal(BigDecimal.ZERO);
        f.setTotalIvaDevuelto(BigDecimal.ZERO);
        f.setCreadoPor(usuarioId);
        f.setCreateDate(LocalDateTime.now());
        f.setUpdateDate(LocalDateTime.now());
        return facturaRepository.saveAndFlush(f);
    }

    /** {@code tipoDocumento} "08" (no exclusivo de NC/ND) por defecto -- los tests de tipo exclusivo lo sobreescriben. */
    private ClienteExoneracion crearExoneracion(String tipoDocumento) {
        ClienteExoneracion e = new ClienteExoneracion();
        e.setClienteId(cliente.getId());
        e.setTipoDocumento(tipoDocumento);
        e.setNumeroDocumento("DOC-" + UUID.randomUUID());
        e.setNombreInstitucion("PROCOMER");
        e.setNumeroArticulo("1");
        e.setFechaEmision(LocalDateTime.now().minusDays(1));
        e.setFechaVencimiento(null);
        e.setPorcentajeExoneracion(new BigDecimal("100.00"));
        e.setActivo(true);
        e.setCreateDate(LocalDateTime.now());
        e.setUpdateDate(LocalDateTime.now());
        return e;
    }

    private String cuerpo(UUID exoneracionId) throws Exception {
        var request = new ControladorDeLineaFacturaDirecta.LineaFacturaDirectaRequest(
                factura.getId(), producto.getId(), exoneracionId);
        return objectMapper.writeValueAsString(request);
    }

    /**
     * Caso 1 del trigger: la carrera descrita en la spec de T-001 -- la exoneración se desactiva
     * DESPUÉS de que la validación Java de {@code LineaFacturaEnsamblador} ya pasó (aquí, saltada
     * a propósito insertando directo vía repositorio). SQLSTATE {@code VX001}.
     */
    @Test
    void lineaFacturaConExoneracionInactiva_devuelve400ConMensajeLimpio() throws Exception {
        ClienteExoneracion exoneracion = crearExoneracion("08");
        exoneracion.setActivo(false);
        exoneracion = clienteExoneracionRepository.saveAndFlush(exoneracion);

        mockMvc.perform(post("/test/linea-factura-directa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(exoneracion.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje")
                        .value("La exoneración referenciada no está vigente (inactiva o vencida)"));
    }

    /** Caso 2 del trigger: la exoneración venció antes del flush. SQLSTATE {@code VX002}. */
    @Test
    void lineaFacturaConExoneracionVencida_devuelve400ConMensajeLimpio() throws Exception {
        ClienteExoneracion exoneracion = crearExoneracion("08");
        exoneracion.setFechaVencimiento(LocalDateTime.now().minusDays(1));
        exoneracion = clienteExoneracionRepository.saveAndFlush(exoneracion);

        mockMvc.perform(post("/test/linea-factura-directa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(exoneracion.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje")
                        .value("La exoneración referenciada no está vigente (inactiva o vencida)"));
    }

    /**
     * Caso 3 del trigger: tipo de exoneración exclusivo de Nota de Crédito/Débito ('01'/'05'/'06'/
     * '07', sección 4.15.1) aplicado a una línea de Factura Electrónica. SQLSTATE {@code VX003}.
     */
    @Test
    void lineaFacturaConExoneracionExclusivaDeNcNd_devuelve400ConMensajeLimpio() throws Exception {
        ClienteExoneracion exoneracion = crearExoneracion("01");
        exoneracion = clienteExoneracionRepository.saveAndFlush(exoneracion);

        mockMvc.perform(post("/test/linea-factura-directa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(exoneracion.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(
                        "El tipo de exoneración referenciado es exclusivo de Nota de Crédito/Débito, "
                                + "no aplica a Factura Electrónica"));
    }

    /**
     * T-001 (corrección, criterio 7) -- ver el javadoc de cabecera de la clase. Inserta una
     * {@code linea_factura} directo vía repositorio (saltando {@code LineaFacturaEnsamblador} a
     * propósito, igual que los 3 casos de arriba) cuyo {@code producto_id} pertenece a una empresa
     * DISTINTA del tenant actual, para que {@code fn_validar_mismo_tenant} (no
     * {@code fn_validar_exoneracion_vigente}) sea quien rechace con {@code P0001}. Verifica que la
     * respuesta HTTP 409 es EXACTAMENTE el mensaje fijo y genérico, y que el cuerpo completo no
     * contiene el {@code empresa_id} de ningún tenant ni texto crudo de Postgres.
     */
    @Test
    void lineaFacturaConProductoDeOtroTenant_devuelve409ConMensajeGenericoSinFugaDeDatos() throws Exception {
        TenantContext.set(UUID.randomUUID());
        Empresa empresaOtroTenant = new Empresa();
        empresaOtroTenant.setRazonSocial("Empresa Otro Tenant S.A.");
        empresaOtroTenant.setNumeroIdentificacion(String.valueOf(
                100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        empresaOtroTenant.setAmbienteHacienda("SANDBOX");
        empresaOtroTenant.setStatus("REGISTRADA");
        empresaOtroTenant.setCreadoPor(usuarioId);
        empresaOtroTenant.setCreateDate(LocalDateTime.now());
        empresaOtroTenant.setUpdateDate(LocalDateTime.now());
        empresaOtroTenant = empresaRepository.save(empresaOtroTenant);
        UUID empresaOtroTenantId = empresaOtroTenant.getId();

        TenantContext.set(empresaOtroTenantId);
        Producto productoDeOtroTenant = crearProducto();

        TenantContext.set(factura.getEmpresaId());

        var request = new ControladorDeLineaFacturaDirecta.LineaFacturaDirectaRequest(
                factura.getId(), productoDeOtroTenant.getId(), null);

        String cuerpoRespuesta = mockMvc.perform(post("/test/linea-factura-directa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La operación viola una regla de integridad del sistema"))
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpoRespuesta)
                .doesNotContain(empresaOtroTenantId.toString())
                .doesNotContain(factura.getEmpresaId().toString())
                .doesNotContain("ERROR:")
                .doesNotContain("Where:")
                .doesNotContain("PL/pgSQL");
    }
}
