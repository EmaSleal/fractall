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

import cr.ac.fractall.catalogo.modelo.Provincia;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de que V15__catalogo_ubicacion_cr.sql siembra las 7 provincias esperadas y que
 * {@link ProvinciaRepository} las expone correctamente.
 *
 * <p>{@code TenantContext.set} es obligatorio aun para esta entidad no tenant-aware -- ver el
 * javadoc de {@code TenantContextDescartable}: {@code EmpresaTenantIdentifierResolver} falla
 * de forma cerrada al abrir CUALQUIER {@code EntityManager} de este {@code SessionFactory} si no
 * hay un {@code empresa_id} resuelto en contexto.
 */
@Testcontainers
@SpringBootTest
class ProvinciaRepositoryTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ProvinciaRepository provinciaRepository;

    @BeforeEach
    void setUp() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void elSeedDeLaMigracionCargaLasSieteProvinciasDeCostaRica() {
        List<Provincia> provincias = provinciaRepository.findAll();

        assertThat(provincias).hasSize(7);
        assertThat(provincias).extracting(Provincia::getCodigo)
                .containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    void sanJoseTieneElCodigoUnoYNombreExacto() {
        Provincia sanJose = provinciaRepository.findById("1").orElseThrow();

        assertThat(sanJose.getNombre()).isEqualTo("San José");
    }
}
