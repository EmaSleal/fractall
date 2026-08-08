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

import cr.ac.fractall.catalogo.modelo.Distrito;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de que V15__catalogo_ubicacion_cr.sql siembra los 475 distritos esperados y que
 * {@link DistritoRepository} soporta tanto el listado en cascada como la verificación de
 * existencia real que usa {@code UbicacionValidator}.
 */
@Testcontainers
@SpringBootTest
class DistritoRepositoryTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DistritoRepository distritoRepository;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void elSeedDeLaMigracionCargaLosCuatrocientosSetentaYCincoDistritosDeCostaRica() {
        List<Distrito> distritos = distritoRepository.findAll();

        assertThat(distritos).hasSize(475);
    }

    @Test
    void findByIdProvinciaCodigoAndIdCantonCodigoDevuelveSoloLosDistritosDeEseCanton() {
        // Cantón San José (provincia '1', cantón '01') tiene 11 distritos.
        List<Distrito> distritosSanJose = distritoRepository
                .findByIdProvinciaCodigoAndIdCantonCodigoOrderByIdCodigoAsc("1", "01");

        assertThat(distritosSanJose).hasSize(11);
        assertThat(distritosSanJose.get(0).getNombre()).isEqualTo("CARMEN");
    }

    @Test
    void existsByCombinacionCompletaEsVerdaderoParaUnaCombinacionRealYFalsoParaUnaInventada() {
        assertThat(distritoRepository.existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo("1", "01", "01"))
                .isTrue();
        assertThat(distritoRepository.existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo("9", "99", "99"))
                .isFalse();
    }
}
