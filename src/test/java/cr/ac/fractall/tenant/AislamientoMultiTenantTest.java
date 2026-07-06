package cr.ac.fractall.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import cr.ac.fractall.catalogo.Cliente;
import cr.ac.fractall.catalogo.ClienteRepository;
import cr.ac.fractall.empresa.Empresa;
import cr.ac.fractall.empresa.EmpresaRepository;
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
    private ClienteRepository clienteRepository;

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
}
