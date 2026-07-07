package cr.ac.fractall.facturacion.repositorio;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivoId;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba dedicada de la combinación llave compuesta ({@code @IdClass}) + {@code @TenantId} en
 * {@link ContadorConsecutivo} -- combinación nunca usada antes en este código base (todas las
 * demás entidades tenant-aware tienen llave sustituta separada del discriminador de tenant, ver
 * {@code TenantAwareEntity}). Mismo espíritu que
 * {@code cr.ac.fractall.tenant.AislamientoMultiTenantTest}, pero enfocada específicamente en si
 * Hibernate aplica correctamente el filtro de tenant cuando {@code empresaId} es TAMBIÉN
 * componente explícito de la llave primaria pasada a {@code findById}.
 */
@Testcontainers
@SpringBootTest
class ContadorConsecutivoAislamientoTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ContadorConsecutivoRepository contadorConsecutivoRepository;

    private Empresa empresaA;
    private Empresa empresaB;

    @BeforeEach
    void setUp() {
        // Ver AislamientoMultiTenantTest: hace falta un empresa_id resuelto en contexto para
        // abrir cualquier EntityManager de este SessionFactory, aunque Usuario/Empresa no sean
        // tenant-aware.
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

        empresaA = empresaRepository.save(nuevaEmpresa("Empresa A S.A.", usuario.getId()));
        empresaB = empresaRepository.save(nuevaEmpresa("Empresa B S.A.", usuario.getId()));
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

    @Test
    void aislaContadoresPorEmpresaAunSiendoElMismoAmbienteYTipo() {
        TenantContext.set(empresaA.getId());
        contadorConsecutivoRepository.save(
                new ContadorConsecutivo(empresaA.getId(), "SANDBOX", "01", 10L));

        TenantContext.set(empresaB.getId());
        contadorConsecutivoRepository.save(
                new ContadorConsecutivo(empresaB.getId(), "SANDBOX", "01", 500L));

        // findAll bajo el tenant de B nunca debe devolver la fila de A, aunque ambas comparten
        // (ambiente, tipoComprobante) y difieren solo en el componente empresaId de la llave.
        List<ContadorConsecutivo> contadoresDeB = contadorConsecutivoRepository.findAll();
        assertThat(contadoresDeB).hasSize(1);
        assertThat(contadoresDeB.get(0).getEmpresaId()).isEqualTo(empresaB.getId());
        assertThat(contadoresDeB.get(0).getValorActual()).isEqualTo(500L);

        TenantContext.set(empresaA.getId());
        List<ContadorConsecutivo> contadoresDeA = contadorConsecutivoRepository.findAll();
        assertThat(contadoresDeA).hasSize(1);
        assertThat(contadoresDeA.get(0).getEmpresaId()).isEqualTo(empresaA.getId());
        assertThat(contadoresDeA.get(0).getValorActual()).isEqualTo(10L);
    }

    @Test
    void findByIdConLlaveDeOtraEmpresaNoDevuelveNadaBajoElTenantActivo() {
        TenantContext.set(empresaA.getId());
        contadorConsecutivoRepository.save(
                new ContadorConsecutivo(empresaA.getId(), "SANDBOX", "01", 1L));

        TenantContext.set(empresaB.getId());
        contadorConsecutivoRepository.save(
                new ContadorConsecutivo(empresaB.getId(), "SANDBOX", "01", 1L));

        // Bajo tenant B, pedir explícitamente por la llave de A (empresaId de A dentro del
        // ContadorConsecutivoId) no debe "ganarle" al filtro de tenant de Hibernate ni resolver
        // por error a la fila de B -- si el filtro y la llave explícita entran en conflicto de
        // forma insegura, este es el punto exacto donde se manifestaría.
        TenantContext.set(empresaB.getId());
        ContadorConsecutivoId llaveDeA = new ContadorConsecutivoId(empresaA.getId(), "SANDBOX", "01");
        assertThat(contadorConsecutivoRepository.findById(llaveDeA)).isEmpty();

        // Y bajo su propio tenant, A sigue viendo su fila normalmente.
        TenantContext.set(empresaA.getId());
        assertThat(contadorConsecutivoRepository.findById(llaveDeA)).isPresent();
    }
}
