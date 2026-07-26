package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.dto.PerfilResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;

@ExtendWith(MockitoExtension.class)
class PerfilServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private RolRepository rolRepository;

    @Mock
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @InjectMocks
    private PerfilService perfilService;

    @Test
    void obtenerRetornaPerfilCompletoConPermisos() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        UUID rolId = UUID.randomUUID();

        Usuario usuario = new Usuario();
        usuario.setNombre("Juan Pérez");
        usuario.setEmail("juan@test.com");
        usuario.setMfaHabilitado(false);

        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rolId);
        membresia.setEstado("ACTIVO");
        membresia.setFechaIngreso(LocalDateTime.now());

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa Prueba S.A.");
        empresa.setNombreComercial("Prueba Corp");
        empresa.setStatus("ACTIVA");

        Rol rol = new Rol();
        rol.setCodigo("ADMIN_EMPRESA");

        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));
        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, "ACTIVO"))
                .thenReturn(Optional.of(membresia));
        when(empresaRepository.findById(empresaId)).thenReturn(Optional.of(empresa));
        when(rolRepository.findById(rolId)).thenReturn(Optional.of(rol));
        when(usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId))
                .thenReturn(List.of("FACTURA_CREAR", "FACTURA_VER"));

        PerfilResponse perfil = perfilService.obtener(usuarioId, empresaId);

        assertThat(perfil.usuarioId()).isEqualTo(usuarioId);
        assertThat(perfil.nombre()).isEqualTo("Juan Pérez");
        assertThat(perfil.email()).isEqualTo("juan@test.com");
        assertThat(perfil.mfaHabilitado()).isFalse();
        assertThat(perfil.empresaActiva()).isNotNull();
        assertThat(perfil.empresaActiva().empresaId()).isEqualTo(empresaId);
        assertThat(perfil.empresaActiva().razonSocial()).isEqualTo("Empresa Prueba S.A.");
        assertThat(perfil.empresaActiva().rolCodigo()).isEqualTo("ADMIN_EMPRESA");
        assertThat(perfil.permisos()).containsExactlyInAnyOrder("FACTURA_CREAR", "FACTURA_VER");
    }

    @Test
    void obtenerRetornaPermisosVaciosCuandoVistaNoTieneFilas() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        UUID rolId = UUID.randomUUID();

        Usuario usuario = new Usuario();
        usuario.setNombre("Ana García");
        usuario.setEmail("ana@test.com");
        usuario.setMfaHabilitado(true);

        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rolId);
        membresia.setEstado("ACTIVO");
        membresia.setFechaIngreso(LocalDateTime.now());

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa Sin Permisos S.A.");
        empresa.setNombreComercial("Sin Permisos Corp");
        empresa.setStatus("ACTIVA");

        Rol rol = new Rol();
        rol.setCodigo("USUARIO");

        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));
        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, "ACTIVO"))
                .thenReturn(Optional.of(membresia));
        when(empresaRepository.findById(empresaId)).thenReturn(Optional.of(empresa));
        when(rolRepository.findById(rolId)).thenReturn(Optional.of(rol));
        when(usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId))
                .thenReturn(List.of());

        PerfilResponse perfil = perfilService.obtener(usuarioId, empresaId);

        assertThat(perfil.permisos()).isEmpty();
        assertThat(perfil.mfaHabilitado()).isTrue();
        assertThat(perfil.empresaActiva().rolCodigo()).isEqualTo("USUARIO");
    }
}
