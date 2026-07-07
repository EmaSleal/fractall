package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivoId;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) de
 * {@link ConsecutivoService}: el bloqueo pesimista de fila (sección 4.9 de
 * {@code arquitectura-facturacion-electronica-cr.md}) solo puede observarse contra un motor
 * real -- H2/mocks no lo reproducen.
 */
@Testcontainers
@SpringBootTest
class ConsecutivoServiceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // El bloqueo pesimista de fila necesita ver conexiones concurrentes reales: con una
        // sola conexión en el pool, el segundo hilo esperaría por la CONEXIÓN, no por la fila,
        // y la prueba de concurrencia no probaría lo que dice probar.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ContadorConsecutivoRepository contadorConsecutivoRepository;

    @Autowired
    private ConsecutivoService consecutivoService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Empresa empresa;

    @BeforeEach
    void setUp() {
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

        Empresa nueva = new Empresa();
        nueva.setRazonSocial("Empresa Consecutivo S.A.");
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuario.getId());
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        empresa = empresaRepository.save(nueva);

        TenantContext.set(empresa.getId());
        contadorConsecutivoRepository.save(
                new ContadorConsecutivo(empresa.getId(), "SANDBOX", "01", 0L));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dosHilosConcurrentesReclamanValoresConsecutivosSinDuplicarNiDejarHuecos() throws Exception {
        UUID empresaId = empresa.getId();
        int cantidadDeLlamadas = 20;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Long>> tareas = Stream
                    .generate(() -> (Callable<Long>) () -> {
                        // Cada hilo necesita su propio TenantContext: es un ThreadLocal.
                        TenantContext.set(empresaId);
                        try {
                            return consecutivoService.siguienteConsecutivo(empresaId, "SANDBOX", "01");
                        } finally {
                            TenantContext.clear();
                        }
                    })
                    .limit(cantidadDeLlamadas)
                    .toList();

            List<Future<Long>> resultados = executor.invokeAll(tareas);
            List<Long> valoresReclamados = resultados.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sorted()
                    .toList();

            // Sin el bloqueo pesimista, dos hilos podrían leer el mismo valor_actual antes de
            // que el otro incremente y confirme -- produciendo duplicados. Aquí se exige la
            // secuencia exacta 1..N, sin huecos ni repeticiones.
            List<Long> esperados = LongStream.rangeClosed(1, cantidadDeLlamadas)
                    .boxed()
                    .toList();
            assertThat(valoresReclamados).containsExactlyElementsOf(esperados);
        } finally {
            executor.shutdown();
        }

        TenantContext.set(empresaId);
        ContadorConsecutivo contadorFinal = contadorConsecutivoRepository
                .findById(new ContadorConsecutivoId(empresaId, "SANDBOX", "01"))
                .orElseThrow();
        assertThat(contadorFinal.getValorActual()).isEqualTo(cantidadDeLlamadas);
    }

    @Test
    void rollbackDeLaTransaccionDelLlamadorRevierteElIncrementoSinDejarHueco() {
        UUID empresaId = empresa.getId();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // Simula: siguienteConsecutivo() se ejecuta como parte de una transacción MÁS AMPLIA
        // (ej. "generar factura completa") que termina en ROLLBACK por otra razón (fallo al
        // guardar la línea de factura, por ejemplo) -- el consecutivo reclamado en esa misma
        // transacción debe revertirse también, sin dejar huecos en la numeración.
        transactionTemplate.execute((TransactionCallback<Void>) (TransactionStatus status) -> {
            TenantContext.set(empresaId);
            long reclamado = consecutivoService.siguienteConsecutivo(empresaId, "SANDBOX", "01");
            assertThat(reclamado).isEqualTo(1L);
            status.setRollbackOnly();
            return null;
        });
        TenantContext.clear();

        TenantContext.set(empresaId);
        ContadorConsecutivo contadorTrasRollback = contadorConsecutivoRepository
                .findById(new ContadorConsecutivoId(empresaId, "SANDBOX", "01"))
                .orElseThrow();
        assertThat(contadorTrasRollback.getValorActual()).isZero();

        // Y una llamada normal después del rollback reclama 1, no 2: no quedó ningún efecto
        // residual de la transacción revertida.
        long siguienteReclamoReal = consecutivoService.siguienteConsecutivo(empresaId, "SANDBOX", "01");
        assertThat(siguienteReclamoReal).isEqualTo(1L);
    }

    /**
     * Ninguna fase anterior siembra {@code contador_consecutivo} para una empresa real -- ni el
     * registro (Fase 4) ni la habilitación (Fase 5). Sin la alta perezosa de
     * {@link ContadorConsecutivoInicializador}, la primera factura de cualquier tenant fallaría
     * permanentemente con {@link ContadorConsecutivoNoEncontradoException}. Esta empresa NO
     * recibe el seed manual de {@code @BeforeEach} -- se crea aparte, deliberadamente sin fila.
     */
    @Test
    void primeraLlamadaSinFilaPreexistenteLaCreaYReclamaElValor1() {
        Empresa empresaSinSeed = crearEmpresaSinFilaDeConsecutivo("Empresa Sin Seed S.A.");
        TenantContext.set(empresaSinSeed.getId());

        long primerValor = consecutivoService.siguienteConsecutivo(empresaSinSeed.getId(), "SANDBOX", "01");
        assertThat(primerValor).isEqualTo(1L);

        long segundoValor = consecutivoService.siguienteConsecutivo(empresaSinSeed.getId(), "SANDBOX", "01");
        assertThat(segundoValor).isEqualTo(2L);
    }

    /**
     * Dos hilos reclamando la PRIMERA factura de la misma empresa nunca vista antes -- ambos
     * intentan crear la fila; exactamente uno gana la carrera de creación (ver
     * {@code REQUIRES_NEW} en {@link ContadorConsecutivoInicializador}), el otro relee la fila ya
     * creada, y ninguno de los dos revienta con una violación de llave primaria sin capturar.
     */
    @Test
    void dosHilosReclamandoLaPrimeraFacturaDeUnaEmpresaNuevaNuncaDuplicanNiFallan() throws Exception {
        Empresa empresaSinSeed = crearEmpresaSinFilaDeConsecutivo("Empresa Carrera Inicial S.A.");
        UUID empresaId = empresaSinSeed.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Long>> tareas = Stream
                    .generate(() -> (Callable<Long>) () -> {
                        TenantContext.set(empresaId);
                        try {
                            return consecutivoService.siguienteConsecutivo(empresaId, "SANDBOX", "01");
                        } finally {
                            TenantContext.clear();
                        }
                    })
                    .limit(2)
                    .toList();

            List<Future<Long>> resultados = executor.invokeAll(tareas);
            List<Long> valoresReclamados = resultados.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sorted()
                    .toList();

            assertThat(valoresReclamados).containsExactly(1L, 2L);
        } finally {
            executor.shutdown();
        }
    }

    private Empresa crearEmpresaSinFilaDeConsecutivo(String razonSocial) {
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

        Empresa nueva = new Empresa();
        nueva.setRazonSocial(razonSocial);
        nueva.setAmbienteHacienda("SANDBOX");
        nueva.setStatus("REGISTRADA");
        nueva.setCreadoPor(usuario.getId());
        nueva.setCreateDate(LocalDateTime.now());
        nueva.setUpdateDate(LocalDateTime.now());
        return empresaRepository.save(nueva);
    }
}
