package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivoId;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) de
 * {@link FacturaService#crear} -- criterio de salida de la Fase 7 (sección 4.9, 4.12-4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}): la orquestación COMPLETA de
 * factura+líneas+comprobante debe ser segura ante concurrencia, no solo la reclamación aislada
 * del consecutivo (ya probada por {@code ConsecutivoServiceTest}).
 *
 * <p>Mismo bootstrap Postgres-solo (sin Vault) que {@code ConsecutivoServiceTest}: ninguna ruta
 * ejercitada aquí toca certificados ni secretos de Vault.
 */
@Testcontainers
@SpringBootTest
class FacturaServiceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Mismo motivo que ConsecutivoServiceTest: la prueba de concurrencia necesita
        // conexiones reales distintas, no una sola conexión serializando a los dos hilos.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
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
    private ContadorConsecutivoRepository contadorConsecutivoRepository;

    @Autowired
    private FacturaRepository facturaRepository;

    @Autowired
    private LineaFacturaRepository lineaFacturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private FacturaService facturaService;

    @Autowired
    private ConsecutivoService consecutivoService;

    private UUID usuarioId;
    private Empresa empresa;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba Factura");
        usuario.setEmail("usuario-factura-" + UUID.randomUUID() + "@fractall.test");
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
        nueva.setRazonSocial("Empresa Factura S.A.");
        nueva.setNumeroIdentificacion(String.valueOf(100_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 900_000_000_000L)));
        nueva.setAmbienteHacienda("SANDBOX");
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

    private Cliente crearCliente(String numeroIdentificacion) {
        Cliente cliente = new Cliente();
        cliente.setNombre("Cliente " + numeroIdentificacion);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion(numeroIdentificacion);
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

    private ClienteExoneracion crearExoneracion(
            UUID clienteId, String tipoDocumento, BigDecimal porcentaje, boolean activo, LocalDateTime vencimiento) {
        ClienteExoneracion exoneracion = new ClienteExoneracion();
        exoneracion.setClienteId(clienteId);
        exoneracion.setTipoDocumento(tipoDocumento);
        exoneracion.setNumeroDocumento("DOC-" + UUID.randomUUID());
        exoneracion.setNombreInstitucion("PROCOMER");
        exoneracion.setNumeroArticulo("1");
        exoneracion.setFechaEmision(LocalDateTime.now().minusDays(1));
        exoneracion.setFechaVencimiento(vencimiento);
        exoneracion.setPorcentajeExoneracion(porcentaje);
        exoneracion.setActivo(activo);
        exoneracion.setCreateDate(LocalDateTime.now());
        exoneracion.setUpdateDate(LocalDateTime.now());
        return clienteExoneracionRepository.save(exoneracion);
    }

    @Test
    void creaFacturaConLineaSinExoneracionYLineaConExoneracionVigenteYCalculaTotalesCorrectos() {
        Cliente cliente = crearCliente("310199" + System.nanoTime() % 1_000_000);
        Producto productoGravado = crearProducto("PROD-A-" + UUID.randomUUID(), new BigDecimal("13.00"));
        Producto productoExonerado = crearProducto("PROD-B-" + UUID.randomUUID(), new BigDecimal("13.00"));

        // Tipo '08' (uno de los no-excluidos del catálogo oficial, sección 4.15.1).
        ClienteExoneracion exoneracion = crearExoneracion(
                cliente.getId(), "08", new BigDecimal("100.00"), true, null);

        LineaFacturaItemRequest lineaSinExoneracion = new LineaFacturaItemRequest(
                productoGravado.getId(), new BigDecimal("2"), new BigDecimal("1000.00000"), null);
        LineaFacturaItemRequest lineaConExoneracion = new LineaFacturaItemRequest(
                productoExonerado.getId(), new BigDecimal("1"), new BigDecimal("2000.00000"), exoneracion.getId());

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null, List.of(lineaSinExoneracion, lineaConExoneracion));

        FacturaResponse response = facturaService.crear(request);

        // Línea 1: subtotal 2000, impuesto 260, sin exoneración.
        // Línea 2: subtotal 2000, impuesto 260, exoneración 100% del IMPUESTO => monto exonerado
        // = 260.00000 (nunca el subtotal comercial -- ver el javadoc de
        // FacturaService#aplicarExoneracion sobre por qué la fórmula opera sobre el impuesto
        // solo, no sobre subtotal+impuesto).
        assertThat(response.lineas()).hasSize(2);
        var linea1 = response.lineas().get(0);
        var linea2 = response.lineas().get(1);

        assertThat(linea1.exoneracionId()).isNull();
        assertThat(linea1.porcentajeExoneracionAplicado()).isNull();
        assertThat(linea1.montoExoneracionAplicado()).isNull();
        assertThat(linea1.subtotal()).isEqualByComparingTo("2000.00000");

        assertThat(linea2.exoneracionId()).isEqualTo(exoneracion.getId());
        assertThat(linea2.porcentajeExoneracionAplicado()).isNotNull();
        assertThat(linea2.montoExoneracionAplicado()).isNotNull();
        assertThat(linea2.porcentajeExoneracionAplicado()).isEqualByComparingTo("100.00");
        assertThat(linea2.montoExoneracionAplicado()).isEqualByComparingTo("260.00000");

        assertThat(response.subtotal()).isEqualByComparingTo("4000.00000");
        // totalImpuesto = (260 + 260) - 260 (exonerado al 100% en línea 2) = 260.00000 -- nunca
        // negativo, y el subtotal comercial de la línea 2 (2000) se sigue cobrando íntegro.
        assertThat(response.totalImpuesto()).isEqualByComparingTo("260.00000");
        assertThat(response.total()).isEqualByComparingTo("4260.00000");

        assertThat(response.consecutivo()).hasSize(20);
        assertThat(response.claveNumerica()).hasSize(50);
        assertThat(response.claveNumerica()).startsWith("506");
        assertThat(response.estado()).isEqualTo("GENERADO");

        // El segmento de consecutivo embebido en claveNumerica debe ser BYTE-IDÉNTICO a la
        // columna consecutivo -- verificado aquí, no asumido (ver formato en ClaveNumericaGenerator:
        // pais(3)+dia(2)+mes(2)+anio(2)+cedula(12) = 21 caracteres antes del consecutivo de 20).
        String segmentoConsecutivoEnClave = response.claveNumerica().substring(21, 41);
        assertThat(segmentoConsecutivoEnClave).isEqualTo(response.consecutivo());

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(response.comprobanteId())
                .orElseThrow();
        assertThat(comprobante.getClaveNumerica()).isEqualTo(response.claveNumerica());
        assertThat(comprobante.getConsecutivo()).isEqualTo(response.consecutivo());
        assertThat(comprobante.getEstado()).isEqualTo("GENERADO");
    }

    @Test
    void clienteInexistenteORDeOtroTenantLanzaExcepcionLimpia() {
        Producto producto = crearProducto("PROD-C-" + UUID.randomUUID(), new BigDecimal("13.00"));
        CrearFacturaRequest request = new CrearFacturaRequest(
                UUID.randomUUID(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, null)));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ClienteNoEncontradoException.class);
    }

    @Test
    void productoInexistenteORDeOtroTenantLanzaExcepcionLimpia() {
        Cliente cliente = crearCliente("310299" + System.nanoTime() % 1_000_000);
        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.TEN, null)));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }

    /**
     * Mismo requisito que el CHECK de {@code factura} en V4 -- validado en Java ANTES de
     * persistir, para no depender de una DataIntegrityViolationException traducida por
     * GlobalExceptionHandler a un 409 de "restricción de unicidad" que sería un mensaje
     * incorrecto (esto es una regla de negocio, no un duplicado).
     */
    @Test
    void condicionVentaCreditoSinPlazoCreditoEsRechazadaLimpiamente() {
        Cliente cliente = crearCliente("310599" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-CV-" + UUID.randomUUID(), new BigDecimal("13.00"));
        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), "02", null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, null)));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(CondicionVentaInvalidaException.class);
    }

    @Test
    void exoneracionDeOtroClienteEsRechazada() {
        Cliente clienteA = crearCliente("310399" + System.nanoTime() % 1_000_000);
        Cliente clienteB = crearCliente("310499" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-D-" + UUID.randomUUID(), new BigDecimal("13.00"));
        ClienteExoneracion exoneracionDeA = crearExoneracion(clienteA.getId(), "08", new BigDecimal("50.00"), true, null);

        CrearFacturaRequest request = new CrearFacturaRequest(
                clienteB.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, exoneracionDeA.getId())));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ExoneracionNoPerteneceAlClienteException.class);
    }

    @Test
    void exoneracionConTipoDocumentoExclusivoDeNotaDeCreditoODebitoEsRechazada() {
        Cliente cliente = crearCliente("310599" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-E-" + UUID.randomUUID(), new BigDecimal("13.00"));
        // '01' es uno de los 4 códigos exclusivos de Nota de Crédito/Débito (sección 4.15.1).
        ClienteExoneracion exoneracionExcluida = crearExoneracion(cliente.getId(), "01", new BigDecimal("50.00"), true, null);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, exoneracionExcluida.getId())));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ExoneracionNoAplicableAFacturaElectronicaException.class);
    }

    @Test
    void exoneracionVencidaEsRechazada() {
        Cliente cliente = crearCliente("310699" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-F-" + UUID.randomUUID(), new BigDecimal("13.00"));
        ClienteExoneracion exoneracionVencida = crearExoneracion(
                cliente.getId(), "08", new BigDecimal("50.00"), true, LocalDateTime.now().minusDays(1));

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, exoneracionVencida.getId())));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ExoneracionNoVigenteException.class);
    }

    @Test
    void exoneracionDesactivadaEsRechazada() {
        Cliente cliente = crearCliente("310799" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-G-" + UUID.randomUUID(), new BigDecimal("13.00"));
        ClienteExoneracion exoneracionInactiva = crearExoneracion(
                cliente.getId(), "08", new BigDecimal("50.00"), false, null);

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, exoneracionInactiva.getId())));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ExoneracionNoVigenteException.class);
    }

    @Test
    void exoneracionInexistenteORDeOtroTenantLanzaExcepcionLimpia() {
        Cliente cliente = crearCliente("310899" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-H-" + UUID.randomUUID(), new BigDecimal("13.00"));

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, UUID.randomUUID())));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(ClienteExoneracionNoEncontradaException.class);
    }

    /**
     * Criterio de salida literal de la Fase 7 (plan-fases-release-1.md): "una prueba de
     * concurrencia real -- dos hilos generando factura simultáneamente para la misma empresa --
     * confirma que no hay consecutivos duplicados ni huecos ante un ROLLBACK forzado", pero a
     * nivel de la orquestación COMPLETA de {@code FacturaService#crear}, no solo de
     * {@code ConsecutivoService} en aislamiento.
     */
    @Test
    void dosHilosCreandoFacturaSimultaneamenteParaLaMismaEmpresaNoDuplicanNiDejanHuecosEnElConsecutivo() throws Exception {
        UUID empresaId = empresa.getId();
        UUID usuario = usuarioId;

        Cliente clienteHilo1 = crearCliente("311199" + System.nanoTime() % 1_000_000);
        Cliente clienteHilo2 = crearCliente("311299" + System.nanoTime() % 1_000_000);
        Producto productoHilo1 = crearProducto("PROD-CONC-1-" + UUID.randomUUID(), new BigDecimal("13.00"));
        Producto productoHilo2 = crearProducto("PROD-CONC-2-" + UUID.randomUUID(), new BigDecimal("13.00"));

        CrearFacturaRequest requestHilo1 = new CrearFacturaRequest(
                clienteHilo1.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(productoHilo1.getId(), BigDecimal.ONE, new BigDecimal("100.00000"), null)));
        CrearFacturaRequest requestHilo2 = new CrearFacturaRequest(
                clienteHilo2.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(productoHilo2.getId(), BigDecimal.ONE, new BigDecimal("200.00000"), null)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tareas = List.of(
                    () -> {
                        TenantContext.set(empresaId);
                        autenticarComo(usuario);
                        try {
                            return facturaService.crear(requestHilo1).consecutivo();
                        } finally {
                            TenantContext.clear();
                            SecurityContextHolder.clearContext();
                        }
                    },
                    () -> {
                        TenantContext.set(empresaId);
                        autenticarComo(usuario);
                        try {
                            return facturaService.crear(requestHilo2).consecutivo();
                        } finally {
                            TenantContext.clear();
                            SecurityContextHolder.clearContext();
                        }
                    });

            List<Future<String>> resultados = executor.invokeAll(tareas);
            List<String> consecutivosReclamados = resultados.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sorted().toList();

            assertThat(consecutivosReclamados).hasSize(2);
            assertThat(consecutivosReclamados.get(0)).isNotEqualTo(consecutivosReclamados.get(1));

            // Sin duplicados ni huecos: los últimos 10 dígitos (segmento "numero" del
            // consecutivo formateado) deben ser exactamente {1, 2}, en cualquier orden entre
            // los 2 hilos.
            List<Long> numerosReclamados = consecutivosReclamados.stream()
                    .map(consecutivo -> Long.parseLong(consecutivo.substring(10)))
                    .sorted()
                    .toList();
            assertThat(numerosReclamados).containsExactly(1L, 2L);
        } finally {
            executor.shutdown();
        }

        TenantContext.set(empresaId);
        long valorFinal = contadorConsecutivoRepository
                .findById(new ContadorConsecutivoId(empresaId, "SANDBOX", "01"))
                .orElseThrow()
                .getValorActual();
        assertThat(valorFinal).isEqualTo(2L);
        assertThat(facturaRepository.count()).isEqualTo(2L);
        assertThat(comprobanteElectronicoRepository.count()).isEqualTo(2L);
    }

    /**
     * Fuerza un ROLLBACK a mitad de la orquestación (producto inexistente en la segunda línea)
     * -- este fallo ocurre ANTES de reclamar consecutivo (todas las líneas se resuelven antes de
     * llamar a {@code ConsecutivoService}), así que confirma que la escritura de factura/líneas
     * en curso también se revierte por completo, sin filas huérfanas.
     */
    @Test
    void rollbackForzadoAntesDeReclamarConsecutivoNoDejaFilasHuerfanas() {
        Cliente cliente = crearCliente("311399" + System.nanoTime() % 1_000_000);
        Producto productoValido = crearProducto("PROD-RB1-" + UUID.randomUUID(), new BigDecimal("13.00"));

        CrearFacturaRequest requestQueFalla = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(
                        new LineaFacturaItemRequest(productoValido.getId(), BigDecimal.ONE, BigDecimal.TEN, null),
                        new LineaFacturaItemRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.TEN, null)));

        assertThatThrownBy(() -> facturaService.crear(requestQueFalla))
                .isInstanceOf(ProductoNoEncontradoException.class);

        assertThat(facturaRepository.count()).isZero();
        assertThat(lineaFacturaRepository.count()).isZero();
        assertThat(comprobanteElectronicoRepository.count()).isZero();

        long contadorTrasFallo = contadorConsecutivoRepository
                .findById(new ContadorConsecutivoId(empresa.getId(), "SANDBOX", "01"))
                .orElseThrow()
                .getValorActual();
        assertThat(contadorTrasFallo).isZero();

        CrearFacturaRequest requestExitosa = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(productoValido.getId(), BigDecimal.ONE, BigDecimal.TEN, null)));
        FacturaResponse respuestaExitosa = facturaService.crear(requestExitosa);

        long numeroReclamado = Long.parseLong(respuestaExitosa.consecutivo().substring(10));
        assertThat(numeroReclamado).isEqualTo(1L);
    }

    /**
     * Fuerza el ROLLBACK DESPUÉS de que {@code ConsecutivoService#siguienteConsecutivo} ya
     * reclamó e incrementó el contador -- distinto del escenario anterior. Se logra con una
     * {@code numeroIdentificacion} sin dígitos: {@code ClaveNumericaGenerator#generar} se invoca
     * DESPUÉS del reclamo del consecutivo en {@code FacturaService#crear}, y
     * {@code Long.parseLong("")} sobre la cédula ya limpiada de no-dígitos lanza
     * {@code NumberFormatException}, forzando el rollback de la transacción completa --
     * incluido el incremento del consecutivo ya escrito. Confirma el criterio de salida de la
     * Fase 7 en el punto más exigente: un ROLLBACK que ocurre DESPUÉS del bloqueo pesimista no
     * debe dejar huecos.
     */
    @Test
    void rollbackForzadoDespuesDeReclamarConsecutivoNoDejaHuecoNiFilaHuerfana() {
        Empresa empresaCedulaInvalida = new Empresa();
        empresaCedulaInvalida.setRazonSocial("Empresa Cédula Inválida S.A.");
        // Sin ningún dígito (y dentro del límite VARCHAR(20) de la columna) -- tras limpiar
        // no-dígitos (ClaveNumericaGenerator#generar) queda en cadena vacía, y
        // Long.parseLong("") lanza NumberFormatException de forma determinística.
        empresaCedulaInvalida.setNumeroIdentificacion("SIN-DIGITOS-AQUI");
        empresaCedulaInvalida.setAmbienteHacienda("SANDBOX");
        empresaCedulaInvalida.setStatus("REGISTRADA");
        empresaCedulaInvalida.setCreadoPor(usuarioId);
        empresaCedulaInvalida.setCreateDate(LocalDateTime.now());
        empresaCedulaInvalida.setUpdateDate(LocalDateTime.now());
        empresaCedulaInvalida = empresaRepository.save(empresaCedulaInvalida);
        UUID empresaCedulaInvalidaId = empresaCedulaInvalida.getId();

        TenantContext.set(empresaCedulaInvalidaId);
        contadorConsecutivoRepository.save(new ContadorConsecutivo(empresaCedulaInvalidaId, "SANDBOX", "01", 0L));
        autenticarComo(usuarioId);

        Cliente cliente = crearCliente("312099" + System.nanoTime() % 1_000_000);
        Producto producto = crearProducto("PROD-RB2-" + UUID.randomUUID(), new BigDecimal("13.00"));

        CrearFacturaRequest request = new CrearFacturaRequest(
                cliente.getId(), null, null, null, null, null,
                List.of(new LineaFacturaItemRequest(producto.getId(), BigDecimal.ONE, BigDecimal.TEN, null)));

        assertThatThrownBy(() -> facturaService.crear(request))
                .isInstanceOf(NumberFormatException.class);

        assertThat(facturaRepository.count()).isZero();
        assertThat(lineaFacturaRepository.count()).isZero();
        assertThat(comprobanteElectronicoRepository.count()).isZero();

        long contadorTrasFallo = contadorConsecutivoRepository
                .findById(new ContadorConsecutivoId(empresaCedulaInvalidaId, "SANDBOX", "01"))
                .orElseThrow()
                .getValorActual();
        assertThat(contadorTrasFallo).isZero();

        // Prueba de que no quedó ningún hueco: el siguiente reclamo real es 1, no 2.
        long siguienteReclamoReal = consecutivoService.siguienteConsecutivo(empresaCedulaInvalidaId, "SANDBOX", "01");
        assertThat(siguienteReclamoReal).isEqualTo(1L);
    }
}
