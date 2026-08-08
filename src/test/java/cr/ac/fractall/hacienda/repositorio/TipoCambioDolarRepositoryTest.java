package cr.ac.fractall.hacienda.repositorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

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

import cr.ac.fractall.hacienda.modelo.TipoCambioDolar;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) de
 * {@link TipoCambioDolarRepository#guardarSiNoExiste} -- el escenario que motivó el upsert
 * (dos peticiones que caen en cache-miss para el mismo día casi al mismo tiempo, ver el javadoc
 * del método) solo puede observarse contra un motor real con una restricción {@code PRIMARY KEY}
 * de verdad; un mock nunca reproduciría la carrera.
 */
@Testcontainers
@SpringBootTest
class TipoCambioDolarRepositoryTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Mismo motivo que ConsecutivoServiceTest: la prueba de concurrencia necesita conexiones
        // reales distintas, no una sola conexión serializando a los dos hilos.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired
    private TipoCambioDolarRepository tipoCambioDolarRepository;

    @BeforeEach
    void setUp() {
        // Obligatorio aun para esta entidad no tenant-aware -- ver el javadoc de
        // TenantContextDescartable / ProvinciaRepositoryTest: EmpresaTenantIdentifierResolver
        // falla de forma cerrada al abrir CUALQUIER EntityManager sin empresa_id en contexto.
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void guardarSiNoExisteInsertaCuandoNoHayFilaParaLaFecha() {
        LocalDate fecha = LocalDate.now().minusDays(1);

        tipoCambioDolarRepository.guardarSiNoExiste(
                fecha, new BigDecimal("530.50"), new BigDecimal("525.30"), LocalDateTime.now());

        TipoCambioDolar guardado = tipoCambioDolarRepository.findById(fecha).orElseThrow();
        assertThat(guardado.getVenta()).isEqualByComparingTo("530.50");
        assertThat(guardado.getCompra()).isEqualByComparingTo("525.30");
    }

    @Test
    void guardarSiNoExisteNoSobreescribeCuandoYaHayFilaParaLaFecha() {
        LocalDate fecha = LocalDate.now().minusDays(2);
        tipoCambioDolarRepository.guardarSiNoExiste(
                fecha, new BigDecimal("500.00"), new BigDecimal("495.00"), LocalDateTime.now());

        tipoCambioDolarRepository.guardarSiNoExiste(
                fecha, new BigDecimal("999.99"), new BigDecimal("888.88"), LocalDateTime.now());

        TipoCambioDolar guardado = tipoCambioDolarRepository.findById(fecha).orElseThrow();
        assertThat(guardado.getVenta()).isEqualByComparingTo("500.00");
        assertThat(guardado.getCompra()).isEqualByComparingTo("495.00");
    }

    /**
     * El escenario real que motivó {@code guardarSiNoExiste}: dos hilos concurrentes, ambos con
     * cache-miss para la MISMA fecha, corriendo {@code guardarSiNoExiste} casi al mismo tiempo.
     * Antes de este fix (un {@code save()} liso sobre una entidad con PK natural), el segundo
     * hilo en llegar reventaba con {@code DataIntegrityViolationException}. Con
     * {@code ON CONFLICT DO NOTHING}, ningún hilo debe fallar, y debe quedar EXACTAMENTE una
     * fila para esa fecha.
     */
    @Test
    void dosHilosConcurrentesConCacheMissParaElMismoDiaNoFallanYDejanUnaSolaFila() throws Exception {
        LocalDate fecha = LocalDate.now().minusDays(3);
        int cantidadDeHilos = 8;

        ExecutorService executor = Executors.newFixedThreadPool(cantidadDeHilos);
        try {
            List<Callable<Void>> tareas = Stream
                    .<Callable<Void>>generate(() -> () -> {
                        // Cada hilo necesita su propio TenantContext: es un ThreadLocal.
                        TenantContext.set(UUID.randomUUID());
                        try {
                            tipoCambioDolarRepository.guardarSiNoExiste(
                                    fecha, new BigDecimal("453.68"), new BigDecimal("447.88"), LocalDateTime.now());
                        } finally {
                            TenantContext.clear();
                        }
                        return null;
                    })
                    .limit(cantidadDeHilos)
                    .toList();

            List<Future<Void>> resultados = executor.invokeAll(tareas);

            // get() relanza cualquier excepción del hilo -- si guardarSiNoExiste todavía
            // lanzara DataIntegrityViolationException, esta línea la haría fallar acá.
            assertThatCode(() -> {
                for (Future<Void> resultado : resultados) {
                    resultado.get();
                }
            }).doesNotThrowAnyException();
        } finally {
            executor.shutdown();
        }

        List<TipoCambioDolar> filas = tipoCambioDolarRepository.findAll().stream()
                .filter(t -> t.getFecha().equals(fecha))
                .toList();
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).getVenta()).isEqualByComparingTo("453.68");
    }
}
