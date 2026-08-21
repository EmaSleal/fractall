package cr.ac.fractall.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.shared.TenantNoResueltoException;

/**
 * Prueba de integración continua (no verificación manual) del mecanismo de
 * aislamiento multi-tenant descrito en la sección 8.3 de
 * {@code arquitectura-facturacion-electronica-cr.md}.
 *
 * <p>Levanta un Postgres real vía Testcontainers y ejecuta Flyway + Hibernate
 * {@code ddl-auto=validate} contra él, de modo que también sirve como
 * verificación desde cero de que las migraciones de Fase 0 y las entidades
 * de Fase 1 son consistentes en cada corrida.
 */
@Testcontainers
@SpringBootTest
class AislamientoMultiTenantTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    // Este test no toca Vault en sí (ninguno de sus 3 casos ejercita cifrado/certificados),
    // pero VaultConfig registra secretLeaseContainer()/certificateContainer() como beans
    // SmartLifecycle que Spring arranca durante el refresh del contexto sin importar si algo
    // los usa -- por eso CUALQUIER @SpringBootTest necesita un Vault real y bootstrapeado,
    // igual que CatalogoControllerTest. Antes este test dependía de un Vault externo ya
    // levantado a mano (docker compose + scripts/vault-setup-local.sh), lo que lo hacía
    // imposible de correr en un runner de CI limpio.
    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();

        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);
    }

    private static void bootstrapAppRole() throws Exception {
        ejecutarVault("auth", "enable", "approle");
        ejecutarVault("secrets", "enable", "transit");
        ejecutarVault("write", "-f", "transit/keys/" + TRANSIT_KEY);

        String politicaHcl = """
                path "secret/data/empresas/*" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/keys/%s" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/datakey/plaintext/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/decrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
        ExecResult resultadoPolitica = VAULT.execInContainer(
                "sh", "-c",
                "cat <<'EOF' | vault policy write " + POLICY_NAME + " -\n" + politicaHcl + "EOF");
        assertThat(resultadoPolitica.getExitCode()).as(resultadoPolitica.getStderr()).isZero();

        ejecutarVault("write", "auth/approle/role/" + APPROLE_NAME,
                "token_policies=" + POLICY_NAME,
                "token_ttl=1h",
                "token_max_ttl=4h",
                "secret_id_ttl=0",
                "token_num_uses=0");

        roleId = ejecutarVault("read", "-field=role_id", "auth/approle/role/" + APPROLE_NAME + "/role-id")
                .getStdout().trim();
        secretId = ejecutarVault("write", "-field=secret_id", "-f", "auth/approle/role/" + APPROLE_NAME + "/secret-id")
                .getStdout().trim();
    }

    private static ExecResult ejecutarVault(String... comandoVault) throws Exception {
        String[] comandoCompleto = new String[comandoVault.length + 1];
        comandoCompleto[0] = "vault";
        System.arraycopy(comandoVault, 0, comandoCompleto, 1, comandoVault.length);

        ExecResult resultado = VAULT.execInContainer(comandoCompleto);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        return resultado;
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
    private FacturaRepository facturaRepository;

    @Autowired
    private LineaFacturaRepository lineaFacturaRepository;

    @Autowired
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    @Autowired
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;

    private Empresa empresaA;
    private Empresa empresaB;

    @BeforeEach
    void setUp() {
        // Hibernate resuelve el tenant actual al ABRIR cualquier EntityManager de este
        // SessionFactory, sin importar si las entidades de esa transacción son
        // tenant-aware (@TenantId) o no: MULTI_TENANT_IDENTIFIER_RESOLVER se registra a
        // nivel de SessionFactory completo, no por entidad. Por eso, incluso para crear
        // Usuario/Empresa (que no son tenant-scoped) hace falta un valor resuelto en
        // contexto; el valor en sí es irrelevante para esas dos entidades porque no
        // tienen columna @TenantId, así que un UUID de descarte es seguro aquí.
        TenantContext.set(UUID.randomUUID());

        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario de prueba");
        usuario.setEmail("usuario-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        usuario = usuarioRepository.save(usuario);

        empresaA = nuevaEmpresa("Empresa A S.A.", usuario.getId());
        empresaB = nuevaEmpresa("Empresa B S.A.", usuario.getId());
        empresaA = empresaRepository.save(empresaA);
        empresaB = empresaRepository.save(empresaB);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Empresa nuevaEmpresa(String razonSocial, UUID creadoPor) {
        Empresa empresa = new Empresa();
        empresa.setRazonSocial(razonSocial);
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");
        empresa.setCreadoPor(creadoPor);
        empresa.setCreateDate(LocalDateTime.now());
        empresa.setUpdateDate(LocalDateTime.now());
        return empresa;
    }

    private static Producto nuevoProducto(String codigo) {
        Producto producto = new Producto();
        producto.setCodigo(codigo);
        producto.setDescripcion("Producto de prueba");
        producto.setCodigoCabys("8399000000000");
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setCodigoUnidadFe("Unid");
        producto.setPrecioVenta(new BigDecimal("1000.00000"));
        producto.setGravado(true);
        producto.setPorcentajeImpuesto(new BigDecimal("13.00"));
        producto.setActivo(true);
        producto.setCreateDate(LocalDateTime.now());
        producto.setUpdateDate(LocalDateTime.now());
        return producto;
    }

    private static Factura nuevaFactura(UUID clienteId, UUID creadoPor) {
        Factura factura = new Factura();
        factura.setClienteId(clienteId);
        factura.setCondicionVenta("01");
        factura.setMedioPago("01");
        factura.setMoneda("CRC");
        factura.setTipoCambio(new BigDecimal("1.00000"));
        factura.setSubtotal(new BigDecimal("1000.00000"));
        factura.setTotalImpuesto(new BigDecimal("130.00000"));
        factura.setTotal(new BigDecimal("1130.00000"));
        factura.setTotalIvaDevuelto(BigDecimal.ZERO);
        factura.setCreadoPor(creadoPor);
        factura.setCreateDate(LocalDateTime.now());
        factura.setUpdateDate(LocalDateTime.now());
        return factura;
    }

    private static LineaFactura nuevaLineaFactura(UUID facturaId, UUID productoId) {
        LineaFactura linea = new LineaFactura();
        linea.setFacturaId(facturaId);
        linea.setProductoId(productoId);
        linea.setNumeroLinea(1);
        linea.setCantidad(BigDecimal.ONE);
        linea.setPrecioUnitario(new BigDecimal("1000.00000"));
        linea.setSubtotal(new BigDecimal("1000.00000"));
        linea.setCodigoCabysAplicado("8399000000000");
        linea.setGravadoAplicado(true);
        linea.setPorcentajeImpuestoAplicado(new BigDecimal("13.00"));
        linea.setTipoTransaccion("01");
        linea.setIvaCobradoFabrica(BigDecimal.ZERO);
        return linea;
    }

    private static ComprobanteElectronico nuevoComprobante(
            UUID facturaId, String tipoComprobante, String consecutivo, String claveNumerica) {
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(facturaId);
        comprobante.setAmbienteHacienda("SANDBOX");
        comprobante.setTipoComprobante(tipoComprobante);
        comprobante.setConsecutivo(consecutivo);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("GENERADO");
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(LocalDateTime.now());
        comprobante.setIntentosConsulta(0);
        return comprobante;
    }

    private static FacturaInformacionReferencia nuevaInformacionReferencia(
            UUID facturaId, String codigo, String codigoReferenciaOtro) {
        FacturaInformacionReferencia referencia = new FacturaInformacionReferencia();
        referencia.setFacturaId(facturaId);
        referencia.setOrden((short) 1);
        referencia.setTipoDocIr("01");
        referencia.setFechaEmisionIr(LocalDateTime.now());
        referencia.setCodigo(codigo);
        referencia.setCodigoReferenciaOtro(codigoReferenciaOtro);
        return referencia;
    }

    private static Throwable raizDe(Throwable error) {
        Throwable causa = error;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        return causa;
    }

    private static Cliente nuevoCliente(String nombre, String numeroIdentificacion) {
        Cliente cliente = new Cliente();
        cliente.setNombre(nombre);
        cliente.setTipoIdentificacion("02");
        cliente.setNumeroIdentificacion(numeroIdentificacion);
        cliente.setRequiereFacturaElectronica(false);
        cliente.setCreateDate(LocalDateTime.now());
        cliente.setUpdateDate(LocalDateTime.now());
        return cliente;
    }

    @Test
    void aislaClientesPorEmpresaEnAmbasDirecciones() {
        TenantContext.set(empresaA.getId());
        clienteRepository.save(nuevoCliente("Cliente A1", "111111111"));
        clienteRepository.save(nuevoCliente("Cliente A2", "222222222"));

        TenantContext.set(empresaB.getId());
        clienteRepository.save(nuevoCliente("Cliente B1", "333333333"));

        List<Cliente> clientesDeB = clienteRepository.findAll();
        assertThat(clientesDeB).hasSize(1);
        assertThat(clientesDeB.get(0).getNombre()).isEqualTo("Cliente B1");

        TenantContext.set(empresaA.getId());
        List<Cliente> clientesDeA = clienteRepository.findAll();
        assertThat(clientesDeA).hasSize(2);
        assertThat(clientesDeA).extracting(Cliente::getNombre)
                .containsExactlyInAnyOrder("Cliente A1", "Cliente A2");
    }

    @Test
    void bloqueaConsultaSinTenantEnContexto() {
        TenantContext.clear();

        Exception excepcion = assertThrows(Exception.class, () -> clienteRepository.findAll());

        Throwable causaRaiz = excepcion;
        while (causaRaiz.getCause() != null && causaRaiz.getCause() != causaRaiz) {
            causaRaiz = causaRaiz.getCause();
        }
        assertThat(causaRaiz).isInstanceOf(TenantNoResueltoException.class);
    }

    @Test
    void restriccionUnicaDeIdentificacionEsPorEmpresaNoGlobal() {
        String mismoNumero = "999999999";

        TenantContext.set(empresaA.getId());
        clienteRepository.save(nuevoCliente("Cliente A", mismoNumero));

        TenantContext.set(empresaB.getId());
        clienteRepository.save(nuevoCliente("Cliente B", mismoNumero));

        TenantContext.set(empresaA.getId());
        assertThat(clienteRepository.findAll()).hasSize(1);

        TenantContext.set(empresaB.getId());
        assertThat(clienteRepository.findAll()).hasSize(1);
    }

    /**
     * Cubre el escenario de punta a punta de la sección 8.3 (alta de producto/cliente + factura
     * con línea) para las entidades tenant-aware que {@code aislaClientesPorEmpresaEnAmbasDirecciones}
     * no ejercita, y el caso puntual de fuga por JOIN: {@code @TenantId} de Hibernate NO aplica a
     * queries nativas, así que {@code FacturaRepository.findClienteNombreByFacturaId} filtraba
     * antes solo por {@code facturaId} -- cualquier consulta directa con el ID de una factura de
     * otra empresa habría devuelto el nombre de su cliente. Ahora exige también {@code empresaId}.
     */
    @Test
    void aislaFacturaLineaYProductoIncluyendoConsultaNativaConJoin() {
        TenantContext.set(empresaA.getId());
        Cliente clienteA = clienteRepository.save(nuevoCliente("Cliente A", "444444444"));
        Producto productoA = productoRepository.save(nuevoProducto("PROD-A"));
        Factura facturaA = facturaRepository.save(nuevaFactura(clienteA.getId(), empresaA.getCreadoPor()));
        lineaFacturaRepository.save(nuevaLineaFactura(facturaA.getId(), productoA.getId()));

        TenantContext.set(empresaB.getId());
        Cliente clienteB = clienteRepository.save(nuevoCliente("Cliente B", "555555555"));
        Producto productoB = productoRepository.save(nuevoProducto("PROD-B"));
        Factura facturaB = facturaRepository.save(nuevaFactura(clienteB.getId(), empresaB.getCreadoPor()));
        lineaFacturaRepository.save(nuevaLineaFactura(facturaB.getId(), productoB.getId()));

        TenantContext.set(empresaA.getId());
        assertThat(facturaRepository.findAll()).extracting(Factura::getId).containsExactly(facturaA.getId());
        assertThat(productoRepository.findAll()).extracting(Producto::getId).containsExactly(productoA.getId());
        assertThat(lineaFacturaRepository.findAll()).extracting(LineaFactura::getFacturaId)
                .containsExactly(facturaA.getId());
        assertThat(facturaRepository.findClienteNombreByFacturaId(facturaA.getId(), empresaA.getId()))
                .contains("Cliente A");
        assertThat(facturaRepository.findClienteNombreByFacturaId(facturaB.getId(), empresaA.getId()))
                .as("la query nativa con JOIN no debe devolver el cliente de una factura de otra empresa")
                .isEmpty();
    }

    /**
     * Fase A de Release 2: {@code factura.factura_referencia_id} pasa por
     * {@code fn_validar_mismo_tenant} igual que {@code cliente_id}/{@code producto_id}/
     * {@code exoneracion_id} -- ver el comentario de cabecera de
     * {@code V18__factura_referencia_y_tope_nota_credito.sql}.
     */
    @Test
    void notaCreditoNoPuedeReferenciarFacturaDeOtroTenant() {
        TenantContext.set(empresaA.getId());
        Cliente clienteA = clienteRepository.save(nuevoCliente("Cliente Origen A", "600000001"));
        Factura facturaOrigenA = facturaRepository.save(nuevaFactura(clienteA.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigenA.getId(), "01", "origen-tenant-01", "clave-origen-tenant-0001"));

        TenantContext.set(empresaB.getId());
        Cliente clienteB = clienteRepository.save(nuevoCliente("Cliente B", "600000002"));
        Factura notaCreditoB = nuevaFactura(clienteB.getId(), empresaB.getCreadoPor());
        notaCreditoB.setFacturaReferenciaId(facturaOrigenA.getId());

        Exception excepcion = assertThrows(Exception.class, () -> facturaRepository.saveAndFlush(notaCreditoB));
        assertThat(raizDe(excepcion).getMessage()).contains("Referencia cruzada entre tenants");
    }

    /**
     * Regla de negocio 7: solo Factura Electrónica (tipo {@code 01}) puede ser el documento de
     * referencia -- ver {@code fn_validar_referencia_es_factura_electronica} en
     * {@code V18__factura_referencia_y_tope_nota_credito.sql}. El catálogo {@code TipoDocumentoIR}
     * permite técnicamente encadenar NC/ND, pero se restringe deliberadamente por alcance.
     */
    @Test
    void referenciaDebeSerFacturaElectronicaNoOtraNotaCreditoDebito() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Ref Tipo Invalido", "600000012"));

        Factura documentoNoFactura = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(documentoNoFactura.getId(), "03", "nc-no-factura-01", "clave-nc-no-factura-0001"));

        Factura segundaNc = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        segundaNc.setFacturaReferenciaId(documentoNoFactura.getId());

        Exception excepcion = assertThrows(Exception.class, () -> facturaRepository.saveAndFlush(segundaNc));
        assertThat(raizDe(excepcion).getMessage()).contains("debe ser una Factura Electrónica");
    }

    /**
     * Fase A de Release 2: {@code fn_validar_tope_nota_credito} se engancha en
     * {@code comprobante_electronico} (no en {@code factura}) porque
     * {@code tipo_comprobante} vive únicamente ahí -- ver el comentario de cabecera de
     * {@code V18__factura_referencia_y_tope_nota_credito.sql} para el detalle de por qué la fila
     * {@code factura} ya existe en ese punto de la transacción.
     */
    @Test
    void topeMontoNotaCreditoBloqueaExcesoSobreFacturaOrigen() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Origen Tope", "600000003"));
        Factura facturaOrigen = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigen.getId(), "01", "origen-tope-01", "clave-origen-tope-0001"));
        // facturaOrigen.total = 1130.00000 (default de nuevaFactura)

        Factura notaCredito = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        notaCredito.setFacturaReferenciaId(facturaOrigen.getId());
        notaCredito.setTotal(new BigDecimal("2000.00000"));
        notaCredito = facturaRepository.saveAndFlush(notaCredito);

        ComprobanteElectronico comprobanteNc = nuevoComprobante(
                notaCredito.getId(), "03", "nc-tope-01", "clave-tope-excede-0001");

        Exception excepcion = assertThrows(Exception.class,
                () -> comprobanteElectronicoRepository.saveAndFlush(comprobanteNc));
        assertThat(raizDe(excepcion).getMessage()).containsIgnoringCase("excede");
    }

    /**
     * Contraparte de {@link #topeMontoNotaCreditoBloqueaExcesoSobreFacturaOrigen}: dos NC
     * parciales cuya suma NO excede el total de la factura origen deben poder insertarse ambas.
     */
    @Test
    void segundaNotaCreditoParcialSePermiteMientrasNoExcedaTotal() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Origen Parcial", "600000004"));
        Factura facturaOrigen = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigen.getId(), "01", "origen-parcial-01", "clave-origen-parcial-0001"));
        // facturaOrigen.total = 1130.00000 (default de nuevaFactura)

        Factura primeraNc = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        primeraNc.setFacturaReferenciaId(facturaOrigen.getId());
        primeraNc.setTotal(new BigDecimal("500.00000"));
        primeraNc = facturaRepository.saveAndFlush(primeraNc);
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(primeraNc.getId(), "03", "nc-parcial-01", "clave-nc-parcial-0001"));

        Factura segundaNc = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        segundaNc.setFacturaReferenciaId(facturaOrigen.getId());
        segundaNc.setTotal(new BigDecimal("600.00000"));
        segundaNc = facturaRepository.saveAndFlush(segundaNc);

        ComprobanteElectronico comprobanteSegundaNc = comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(segundaNc.getId(), "03", "nc-parcial-02", "clave-nc-parcial-0002"));

        assertThat(comprobanteSegundaNc.getId()).isNotNull();
    }

    /**
     * Triangulación del límite exacto: regla 3 dice "no puede exceder", no "debe ser menor" --
     * el trigger usa {@code >}, no {@code >=} (ver {@code fn_validar_tope_nota_credito} en
     * V18). Una NC cuyo total iguala exactamente el total de la factura origen (anulación total,
     * regla 2) debe permitirse.
     */
    @Test
    void topeNotaCreditoPermiteSumaExactamenteIgualAlTotal() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Origen Exacto", "600000005"));
        Factura facturaOrigen = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigen.getId(), "01", "origen-exacto-01", "clave-origen-exacto-0001"));
        // facturaOrigen.total = 1130.00000 (default de nuevaFactura)

        Factura notaCredito = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        notaCredito.setFacturaReferenciaId(facturaOrigen.getId());
        notaCredito.setTotal(new BigDecimal("1130.00000"));
        notaCredito = facturaRepository.saveAndFlush(notaCredito);

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(notaCredito.getId(), "03", "nc-exacto-01", "clave-nc-exacto-0001"));

        assertThat(comprobante.getId()).isNotNull();
    }

    /**
     * Triangulación de acumulación: el trigger suma TODAS las NC previas contra la misma
     * factura origen, no solo compara contra la última insertada. Tres NC parciales que suman
     * exactamente el total se permiten; una cuarta que excede por el margen más pequeño posible
     * (precisión de {@code NUMERIC(14,5)}) se bloquea -- confirma que la comparación no pierde
     * precisión al acumular sobre más de dos filas.
     */
    @Test
    void topeNotaCreditoAcumulaTresParcialesYBloqueaElMinimoExcesoEnLaCuarta() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Origen Acumulado", "600000006"));
        Factura facturaOrigen = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigen.getId(), "01", "origen-acum-01", "clave-origen-acum-0001"));
        // facturaOrigen.total = 1130.00000 (default de nuevaFactura)

        BigDecimal[] montosParciales = {
                new BigDecimal("400.00000"), new BigDecimal("400.00000"), new BigDecimal("330.00000")
        };
        for (int i = 0; i < montosParciales.length; i++) {
            Factura nc = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
            nc.setFacturaReferenciaId(facturaOrigen.getId());
            nc.setTotal(montosParciales[i]);
            nc = facturaRepository.saveAndFlush(nc);
            comprobanteElectronicoRepository.saveAndFlush(
                    nuevoComprobante(nc.getId(), "03", "nc-acum-0" + i, "clave-nc-acum-000" + i));
        }
        // Suma acumulada tras las 3 anteriores: exactamente 1130.00000 (el total de la factura origen).

        Factura cuartaNc = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        cuartaNc.setFacturaReferenciaId(facturaOrigen.getId());
        cuartaNc.setTotal(new BigDecimal("0.00001"));
        cuartaNc = facturaRepository.saveAndFlush(cuartaNc);

        ComprobanteElectronico comprobanteCuartaNc = nuevoComprobante(
                cuartaNc.getId(), "03", "nc-acum-04", "clave-nc-acum-0004");
        Exception excepcion = assertThrows(Exception.class,
                () -> comprobanteElectronicoRepository.saveAndFlush(comprobanteCuartaNc));
        assertThat(raizDe(excepcion).getMessage()).containsIgnoringCase("excede");
    }

    /**
     * Triangulación de la exclusión explícita de regla 3: "No aplica a ND -- Nota de Débito
     * agrega monto, no lo resta". El {@code WHEN} de {@code trg_validar_tope_nota_credito} filtra
     * {@code tipo_comprobante = '03'}, así que una ND con total muy superior al de la factura
     * origen debe insertarse sin que el trigger siquiera se ejecute.
     */
    @Test
    void notaDebitoNoTieneTopeDeMontoSobreFacturaOrigen() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Origen ND", "600000007"));
        Factura facturaOrigen = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));
        comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(facturaOrigen.getId(), "01", "origen-nd-01", "clave-origen-nd-0001"));
        // facturaOrigen.total = 1130.00000 (default de nuevaFactura)

        Factura notaDebito = nuevaFactura(cliente.getId(), empresaA.getCreadoPor());
        notaDebito.setFacturaReferenciaId(facturaOrigen.getId());
        notaDebito.setTotal(new BigDecimal("50000.00000"));
        notaDebito = facturaRepository.saveAndFlush(notaDebito);

        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.saveAndFlush(
                nuevoComprobante(notaDebito.getId(), "02", "nd-sin-tope-01", "clave-nd-sin-tope-0001"));

        assertThat(comprobante.getId()).isNotNull();
    }

    /**
     * Triangulación de regla 5 (motivo obligatorio): el CHECK sin nombre de
     * {@code factura_informacion_referencia} (V11) exige {@code codigo_referencia_otro}
     * únicamente cuando {@code codigo = '99'} -- el campo que Hacienda documenta como
     * condicionalmente obligatorio en ese caso ({@code CodigoReferenciaOTRO} en los XSD
     * oficiales), no {@code razon} (libre siempre, sin obligatoriedad condicional en el XSD).
     * Esta regla ya existía antes de Release 2 pero no tenía cobertura de test explícita.
     */
    @Test
    void codigoReferenciaOtroEsObligatorioCuandoCodigoEsOtros() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Ref Otros Null", "600000008"));
        Factura factura = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));

        FacturaInformacionReferencia referencia = nuevaInformacionReferencia(factura.getId(), "99", null);

        assertThrows(Exception.class,
                () -> facturaInformacionReferenciaRepository.saveAndFlush(referencia));
    }

    /**
     * Contraparte de {@link #codigoReferenciaOtroEsObligatorioCuandoCodigoEsOtros}: el CHECK
     * exige {@code codigo_referencia_otro IS NOT NULL AND codigo_referencia_otro <> ''}, no solo
     * {@code IS NOT NULL} -- una cadena vacía no cuenta como descripción real y debe bloquear
     * igual que {@code NULL}.
     */
    @Test
    void codigoReferenciaOtroVacioNoCuentaComoValidoCuandoCodigoEsOtros() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Ref Otros Vacio", "600000009"));
        Factura factura = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));

        FacturaInformacionReferencia referencia = nuevaInformacionReferencia(factura.getId(), "99", "");

        assertThrows(Exception.class,
                () -> facturaInformacionReferenciaRepository.saveAndFlush(referencia));
    }

    /**
     * Contraparte positiva: {@code codigo = '99'} con un {@code codigo_referencia_otro} real sí
     * se permite.
     */
    @Test
    void codigoReferenciaOtroPresenteConCodigoOtrosSePermite() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Ref Otros Con Descripcion", "600000010"));
        Factura factura = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));

        FacturaInformacionReferencia referencia = nuevaInformacionReferencia(
                factura.getId(), "99", "Descripción puntual del código de referencia utilizado");
        FacturaInformacionReferencia guardada =
                facturaInformacionReferenciaRepository.saveAndFlush(referencia);

        assertThat(guardada.getId()).isNotNull();
    }

    /**
     * Triangulación de la mitad "opcional" de regla 5: con un {@code codigo} distinto de
     * {@code '99'} (p. ej. {@code '01'}, Anula documento de referencia),
     * {@code codigo_referencia_otro} puede ser {@code NULL} sin violar el CHECK.
     */
    @Test
    void codigoReferenciaOtroEsOpcionalCuandoCodigoNoEsOtros() {
        TenantContext.set(empresaA.getId());
        Cliente cliente = clienteRepository.save(nuevoCliente("Cliente Ref No Otros", "600000011"));
        Factura factura = facturaRepository.save(nuevaFactura(cliente.getId(), empresaA.getCreadoPor()));

        FacturaInformacionReferencia referencia = nuevaInformacionReferencia(factura.getId(), "01", null);
        FacturaInformacionReferencia guardada =
                facturaInformacionReferenciaRepository.saveAndFlush(referencia);

        assertThat(guardada.getId()).isNotNull();
    }
}
