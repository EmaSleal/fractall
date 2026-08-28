package cr.ac.fractall.seguridad.servicio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;

@ExtendWith(MockitoExtension.class)
class PermisoGuardTest {

    @Mock
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @InjectMocks
    private PermisoGuard permisoGuard;

    /**
     * Prueba clave del diseño: {@code permisos_efectivos} (V3) no filtra por
     * {@code ue.estado}, así que una membresía SUSPENDIDO resuelve igualmente el catálogo
     * completo de permisos de su rol. Si {@code PermisoGuard} solo revisara los códigos de
     * permiso, suspender a un administrador no tendría ningún efecto sobre los endpoints
     * protegidos. Este test prueba que el chequeo de estado ACTIVO ocurre ANTES de leer
     * permisos_efectivos: la membresía no está ACTIVO, así que el guard debe negar el acceso
     * sin siquiera consultar los códigos de permiso.
     */
    @Test
    void membresiaSuspendidaEsDenegadaAunqueTengaElPermisoEnPermisosEfectivos() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();

        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, "ACTIVO"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permisoGuard.exigir(usuarioId, empresaId, "usuario.invitar"))
                .isInstanceOf(PermisoDenegadoException.class);

        verify(usuarioEmpresaRepository, never()).findPermisoCodigos(usuarioId, empresaId);
    }

    @Test
    void membresiaActivaSinElPermisoEsDenegada() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setEstado("ACTIVO");

        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, "ACTIVO"))
                .thenReturn(Optional.of(membresia));
        when(usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId))
                .thenReturn(List.of("USUARIO_VER"));

        assertThatThrownBy(() -> permisoGuard.exigir(usuarioId, empresaId, "usuario.invitar"))
                .isInstanceOf(PermisoDenegadoException.class);
    }

    @Test
    void membresiaActivaConElPermisoPasaSinLanzarExcepcion() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setEstado("ACTIVO");

        when(usuarioEmpresaRepository.findByUsuarioIdAndEmpresaIdAndEstado(usuarioId, empresaId, "ACTIVO"))
                .thenReturn(Optional.of(membresia));
        when(usuarioEmpresaRepository.findPermisoCodigos(usuarioId, empresaId))
                .thenReturn(List.of("usuario.invitar", "usuario.ver"));

        permisoGuard.exigir(usuarioId, empresaId, "usuario.invitar");
    }
}
