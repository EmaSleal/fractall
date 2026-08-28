package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cr.ac.fractall.seguridad.dto.MiembroResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

/**
 * Prueba unitaria pura (sin contexto de Spring, sin Testcontainers) de la guarda del último
 * administrador de {@link MembresiaAdminService#cambiarRol} (Fase B, PR5b -- ver design.md,
 * sección "MembresiaAdminService" y su Testing Strategy: "Unidad ... último ADMIN_EMPRESA ACTIVO
 * ⇒ 409; penúltimo ⇒ pasa").
 *
 * <p>Esta guarda es estructuralmente irreproducible con un actor real autenticado vía HTTP:
 * {@code usuario.editar_rol} (V20) solo lo tiene {@code ADMIN_EMPRESA}, así que cualquier actor
 * que supere {@link PermisoGuard} contra un objetivo DISTINTO de sí mismo es, por definición, un
 * segundo administrador activo -- el conteo nunca puede ser 1 en ese camino real. Por eso
 * {@code PermisoGuard} se mockea aquí, aislando la regla de negocio de ese acoplamiento (la
 * autogestión, que SÍ es alcanzable vía HTTP, se cubre en {@code UsuarioFlujoInvitacionTest}).
 */
class MembresiaAdminServiceTest {

    private static final String ROL_ADMIN_EMPRESA = "ADMIN_EMPRESA";
    private static final String ROL_CONSULTA = "CONSULTA";
    private static final String ESTADO_ACTIVO = "ACTIVO";

    private final PermisoGuard permisoGuard = mock(PermisoGuard.class);
    private final UsuarioEmpresaRepository usuarioEmpresaRepository = mock(UsuarioEmpresaRepository.class);
    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final RolRepository rolRepository = mock(RolRepository.class);

    private final MembresiaAdminService service =
            new MembresiaAdminService(permisoGuard, usuarioEmpresaRepository, usuarioRepository, rolRepository);

    private Rol rol(String codigo) {
        Rol rol = new Rol();
        ReflectionTestUtils.setField(rol, "id", UUID.randomUUID());
        rol.setCodigo(codigo);
        rol.setNombre(codigo);
        return rol;
    }

    private UsuarioEmpresa membresia(UUID usuarioId, UUID empresaId, UUID rolId) {
        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rolId);
        membresia.setEstado(ESTADO_ACTIVO);
        membresia.setFechaIngreso(LocalDateTime.now());
        return membresia;
    }

    private Usuario usuario(UUID id) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setNombre("Nombre de prueba");
        usuario.setEmail("prueba@fractall.test");
        return usuario;
    }

    @Test
    void cambiarRolDelUnicoAdministradorActivoEsRechazadoConUltimoAdministrador() {
        UUID empresaId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID objetivoId = UUID.randomUUID();
        Rol admin = rol(ROL_ADMIN_EMPRESA);
        Rol consulta = rol(ROL_CONSULTA);
        UsuarioEmpresa objetivo = membresia(objetivoId, empresaId, admin.getId());

        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaId(objetivoId, empresaId))
                .thenReturn(Optional.of(objetivo));
        when(rolRepository.findByCodigo(ROL_CONSULTA)).thenReturn(Optional.of(consulta));
        when(rolRepository.findByCodigo(ROL_ADMIN_EMPRESA)).thenReturn(Optional.of(admin));
        when(usuarioEmpresaRepository.contarAdministradoresActivos(empresaId)).thenReturn(1L);

        assertThatThrownBy(() -> service.cambiarRol(actorId, empresaId, objetivoId, ROL_CONSULTA))
                .isInstanceOf(UltimoAdministradorException.class);

        assertThat(objetivo.getRolId())
                .as("el rol no debe mutar cuando la guarda del último administrador rechaza")
                .isEqualTo(admin.getId());
    }

    @Test
    void cambiarRolConDosAdministradoresActivosPermiteDegradarAlPenultimo() {
        UUID empresaId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID objetivoId = UUID.randomUUID();
        Rol admin = rol(ROL_ADMIN_EMPRESA);
        Rol consulta = rol(ROL_CONSULTA);
        UsuarioEmpresa objetivo = membresia(objetivoId, empresaId, admin.getId());

        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaId(objetivoId, empresaId))
                .thenReturn(Optional.of(objetivo));
        when(rolRepository.findByCodigo(ROL_CONSULTA)).thenReturn(Optional.of(consulta));
        when(rolRepository.findByCodigo(ROL_ADMIN_EMPRESA)).thenReturn(Optional.of(admin));
        when(usuarioEmpresaRepository.contarAdministradoresActivos(empresaId)).thenReturn(2L);
        when(usuarioRepository.findById(objetivoId)).thenReturn(Optional.of(usuario(objetivoId)));

        MiembroResponse respuesta = service.cambiarRol(actorId, empresaId, objetivoId, ROL_CONSULTA);

        assertThat(objetivo.getRolId()).isEqualTo(consulta.getId());
        assertThat(respuesta.rolCodigo()).isEqualTo(ROL_CONSULTA);
    }
}
