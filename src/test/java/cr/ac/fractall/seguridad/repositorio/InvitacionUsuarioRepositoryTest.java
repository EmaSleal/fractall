package cr.ac.fractall.seguridad.repositorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.modelo.InvitacionUsuario;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba de extremo a extremo (Postgres real vía Testcontainers, sin mocks) del índice único
 * parcial {@code ux_invitacion_usuario_pendiente} (V22) -- el único mecanismo que bloquea una
 * segunda invitación viva al mismo correo en la misma empresa. Un mock nunca reproduciría la
 * restricción de motor.
 *
 * <p>{@link InvitacionUsuario} extiende {@code EntidadBase} con un {@code empresaId} plano, NO
 * {@code TenantAwareEntity}/{@code @TenantId} -- ver la discusión 1 de design.md: ambos caminos
 * de consumo (aceptar invitación bajo el tenant del invitado, registrar por invitación bajo
 * {@code TenantContextDescartable}) resuelven la fila ANTES de conocer la empresa que invita.
 */
@Testcontainers
@SpringBootTest
class InvitacionUsuarioRepositoryTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private InvitacionUsuarioRepository invitacionUsuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private RolRepository rolRepository;

    private Empresa empresaA;
    private Empresa empresaB;
    private Usuario invitador;
    private Rol rolAdminEmpresa;

    @BeforeEach
    void setUp() {
        // Ver ContadorConsecutivoAislamientoTest / ProvinciaRepositoryTest: hace falta un
        // empresa_id resuelto en contexto para abrir cualquier EntityManager de este
        // SessionFactory, aunque InvitacionUsuario no sea tenant-aware.
        TenantContext.set(UUID.randomUUID());

        invitador = usuarioRepository.save(nuevoUsuario());
        empresaA = empresaRepository.save(nuevaEmpresa("Empresa A S.A.", invitador.getId()));
        empresaB = empresaRepository.save(nuevaEmpresa("Empresa B S.A.", invitador.getId()));
        rolAdminEmpresa = rolRepository.findByCodigo("ADMIN_EMPRESA").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Usuario nuevoUsuario() {
        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario invitador");
        usuario.setEmail("invitador-" + UUID.randomUUID() + "@fractall.test");
        usuario.setPasswordHash("hash-no-relevante");
        usuario.setEmailVerificado(true);
        usuario.setEstado("ACTIVA");
        usuario.setMfaHabilitado(false);
        usuario.setIntentosFallidos(0);
        usuario.setCreateDate(LocalDateTime.now());
        usuario.setUpdateDate(LocalDateTime.now());
        return usuario;
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

    private InvitacionUsuario nuevaInvitacion(UUID empresaId, String email, String estado, String tokenHash) {
        InvitacionUsuario invitacion = new InvitacionUsuario();
        invitacion.setEmpresaId(empresaId);
        invitacion.setEmail(email);
        invitacion.setRolId(rolAdminEmpresa.getId());
        invitacion.setTokenHash(tokenHash);
        invitacion.setInvitadoPor(invitador.getId());
        invitacion.setExpiraEn(LocalDateTime.now().plusDays(7));
        invitacion.setEstado(estado);
        invitacion.setCreateDate(LocalDateTime.now());
        return invitacion;
    }

    @Test
    void segundaInvitacionPendienteAlMismoCorreoEnLaMismaEmpresaViolaUnicidad() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "duplicado@fractall.test", "PENDIENTE", "hash-1"));

        assertThatThrownBy(() -> invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "duplicado@fractall.test", "PENDIENTE", "hash-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mismoCorreoEnEmpresasDistintasNoViolaUnicidad() {
        TenantContext.set(empresaA.getId());
        InvitacionUsuario invitacionA = invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "compartido@fractall.test", "PENDIENTE", "hash-a"));

        TenantContext.set(empresaB.getId());
        InvitacionUsuario invitacionB = invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaB.getId(), "compartido@fractall.test", "PENDIENTE", "hash-b"));

        assertThat(invitacionA.getId()).isNotEqualTo(invitacionB.getId());
    }

    @Test
    void invitacionAceptadaNoBloqueaUnaNuevaPendienteAlMismoCorreo() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "reenviar@fractall.test", "ACEPTADA", "hash-aceptada"));

        InvitacionUsuario nueva = invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "reenviar@fractall.test", "PENDIENTE", "hash-nueva"));

        assertThat(nueva.getEstado()).isEqualTo("PENDIENTE");
    }

    @Test
    void invitacionRevocadaNoBloqueaUnaNuevaPendienteAlMismoCorreo() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "revocada@fractall.test", "REVOCADA", "hash-revocada"));

        InvitacionUsuario nueva = invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "revocada@fractall.test", "PENDIENTE", "hash-nueva-2"));

        assertThat(nueva.getEstado()).isEqualTo("PENDIENTE");
    }

    @Test
    void lowerEmailColapsaMayusculasParaElIndiceParcial() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "MinusculA@fractall.test", "PENDIENTE", "hash-minuscula"));

        assertThatThrownBy(() -> invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "minuscula@fractall.test", "PENDIENTE", "hash-mayuscula")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByTokenHashDevuelveLaInvitacionCuandoElHashCoincide() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "buscar-token@fractall.test", "PENDIENTE", "hash-unico-buscar"));

        InvitacionUsuario encontrada = invitacionUsuarioRepository.findByTokenHash("hash-unico-buscar").orElseThrow();

        assertThat(encontrada.getEmail()).isEqualTo("buscar-token@fractall.test");
        assertThat(invitacionUsuarioRepository.findByTokenHash("hash-inexistente")).isEmpty();
    }

    @Test
    void findByEmpresaIdAndEmailAndEstadoDevuelveSoloLaFilaEnEseEstado() {
        TenantContext.set(empresaA.getId());
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "buscar-estado@fractall.test", "ACEPTADA", "hash-vieja"));
        invitacionUsuarioRepository.saveAndFlush(
                nuevaInvitacion(empresaA.getId(), "buscar-estado@fractall.test", "PENDIENTE", "hash-viva"));

        InvitacionUsuario viva = invitacionUsuarioRepository
                .findByEmpresaIdAndEmailAndEstado(empresaA.getId(), "buscar-estado@fractall.test", "PENDIENTE")
                .orElseThrow();

        assertThat(viva.getTokenHash()).isEqualTo("hash-viva");
        assertThat(invitacionUsuarioRepository
                .findByEmpresaIdAndEmailAndEstado(empresaA.getId(), "buscar-estado@fractall.test", "REVOCADA"))
                .isEmpty();
    }
}
