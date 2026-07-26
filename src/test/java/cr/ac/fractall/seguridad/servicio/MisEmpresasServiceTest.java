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
import cr.ac.fractall.seguridad.dto.EmpresaResumenResponse;
import cr.ac.fractall.seguridad.modelo.Rol;
import cr.ac.fractall.seguridad.modelo.UsuarioEmpresa;
import cr.ac.fractall.seguridad.repositorio.RolRepository;
import cr.ac.fractall.seguridad.repositorio.UsuarioEmpresaRepository;

@ExtendWith(MockitoExtension.class)
class MisEmpresasServiceTest {

    @Mock
    private UsuarioEmpresaRepository usuarioEmpresaRepository;

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private RolRepository rolRepository;

    @InjectMocks
    private MisEmpresasService misEmpresasService;

    @Test
    void listarRetornaEmpresasActivasDelUsuario() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        UUID rolId = UUID.randomUUID();

        UsuarioEmpresa membresia = new UsuarioEmpresa();
        membresia.setUsuarioId(usuarioId);
        membresia.setEmpresaId(empresaId);
        membresia.setRolId(rolId);
        membresia.setEstado("ACTIVO");
        membresia.setFechaIngreso(LocalDateTime.now());

        Empresa empresa = new Empresa();
        empresa.setRazonSocial("Empresa Test S.A.");
        empresa.setNombreComercial("Test Corp");
        empresa.setStatus("ACTIVA");

        Rol rol = new Rol();
        rol.setCodigo("ADMIN_EMPRESA");

        when(usuarioEmpresaRepository.findByUsuarioIdAndEstado(usuarioId, "ACTIVO"))
                .thenReturn(List.of(membresia));
        when(empresaRepository.findById(empresaId)).thenReturn(Optional.of(empresa));
        when(rolRepository.findById(rolId)).thenReturn(Optional.of(rol));

        List<EmpresaResumenResponse> resultado = misEmpresasService.listar(usuarioId);

        assertThat(resultado).hasSize(1);
        EmpresaResumenResponse respuesta = resultado.get(0);
        assertThat(respuesta.empresaId()).isEqualTo(empresaId);
        assertThat(respuesta.razonSocial()).isEqualTo("Empresa Test S.A.");
        assertThat(respuesta.nombreComercial()).isEqualTo("Test Corp");
        assertThat(respuesta.rolCodigo()).isEqualTo("ADMIN_EMPRESA");
        assertThat(respuesta.estadoMembresia()).isEqualTo("ACTIVO");
    }

    @Test
    void listarRetornaListaVaciaCuandoNoHayEmpresasActivas() {
        UUID usuarioId = UUID.randomUUID();

        when(usuarioEmpresaRepository.findByUsuarioIdAndEstado(usuarioId, "ACTIVO"))
                .thenReturn(List.of());

        List<EmpresaResumenResponse> resultado = misEmpresasService.listar(usuarioId);

        assertThat(resultado).isEmpty();
    }

    @Test
    void listarRetornaMultiplesEmpresasCuandoUsuarioTieneMas() {
        UUID usuarioId = UUID.randomUUID();
        UUID empresaId1 = UUID.randomUUID();
        UUID empresaId2 = UUID.randomUUID();
        UUID rolId1 = UUID.randomUUID();
        UUID rolId2 = UUID.randomUUID();

        UsuarioEmpresa membresia1 = new UsuarioEmpresa();
        membresia1.setUsuarioId(usuarioId);
        membresia1.setEmpresaId(empresaId1);
        membresia1.setRolId(rolId1);
        membresia1.setEstado("ACTIVO");
        membresia1.setFechaIngreso(LocalDateTime.now());

        UsuarioEmpresa membresia2 = new UsuarioEmpresa();
        membresia2.setUsuarioId(usuarioId);
        membresia2.setEmpresaId(empresaId2);
        membresia2.setRolId(rolId2);
        membresia2.setEstado("ACTIVO");
        membresia2.setFechaIngreso(LocalDateTime.now());

        Empresa empresa1 = new Empresa();
        empresa1.setRazonSocial("Empresa Uno S.A.");
        empresa1.setNombreComercial("Corp Uno");
        empresa1.setStatus("ACTIVA");

        Empresa empresa2 = new Empresa();
        empresa2.setRazonSocial("Empresa Dos S.A.");
        empresa2.setNombreComercial("Corp Dos");
        empresa2.setStatus("ACTIVA");

        Rol rol1 = new Rol();
        rol1.setCodigo("ADMIN_EMPRESA");

        Rol rol2 = new Rol();
        rol2.setCodigo("USUARIO");

        when(usuarioEmpresaRepository.findByUsuarioIdAndEstado(usuarioId, "ACTIVO"))
                .thenReturn(List.of(membresia1, membresia2));
        when(empresaRepository.findById(empresaId1)).thenReturn(Optional.of(empresa1));
        when(empresaRepository.findById(empresaId2)).thenReturn(Optional.of(empresa2));
        when(rolRepository.findById(rolId1)).thenReturn(Optional.of(rol1));
        when(rolRepository.findById(rolId2)).thenReturn(Optional.of(rol2));

        List<EmpresaResumenResponse> resultado = misEmpresasService.listar(usuarioId);

        assertThat(resultado).hasSize(2);
        assertThat(resultado).extracting(EmpresaResumenResponse::razonSocial)
                .containsExactlyInAnyOrder("Empresa Uno S.A.", "Empresa Dos S.A.");
    }
}
