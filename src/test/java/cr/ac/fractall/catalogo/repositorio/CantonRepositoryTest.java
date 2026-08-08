package cr.ac.fractall.catalogo.repositorio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

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

import cr.ac.fractall.catalogo.modelo.Canton;
import cr.ac.fractall.catalogo.modelo.CantonId;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de que V15__catalogo_ubicacion_cr.sql siembra los 81 cantones esperados y que
 * {@link CantonRepository} filtra correctamente por provincia (combo en cascada).
 */
@Testcontainers
@SpringBootTest
class CantonRepositoryTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CantonRepository cantonRepository;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void elSeedDeLaMigracionCargaLosOchentaYUnCantonesDeCostaRica() {
        List<Canton> cantones = cantonRepository.findAll();

        assertThat(cantones).hasSize(81);
    }

    @Test
    void findByIdProvinciaCodigoDevuelveSoloLosCantonesDeEsaProvincia() {
        // San José (provincia '1') tiene 20 cantones en el catálogo oficial.
        List<Canton> cantonesSanJose = cantonRepository.findByIdProvinciaCodigoOrderByIdCodigoAsc("1");

        assertThat(cantonesSanJose).hasSize(20);
        assertThat(cantonesSanJose).allMatch(c -> "1".equals(c.getId().getProvinciaCodigo()));
        assertThat(cantonesSanJose.get(0).getId().getCodigo()).isEqualTo("01");
    }

    @Test
    void findByIdConLlaveCompuestaDevuelveElCantonEsperado() {
        Canton escazu = cantonRepository.findById(new CantonId("1", "02")).orElseThrow();

        assertThat(escazu.getNombre()).isEqualTo("Escazú");
    }
}
