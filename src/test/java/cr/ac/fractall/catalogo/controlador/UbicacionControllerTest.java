package cr.ac.fractall.catalogo.controlador;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración a nivel HTTP de {@link UbicacionController} -- los 3 endpoints de solo
 * lectura que alimentan los combos en cascada provincia -&gt; cantón -&gt; distrito del frontend,
 * contra el seed real de V15__catalogo_ubicacion_cr.sql.
 *
 * <p>Sin Vault: a diferencia de {@code CatalogoControllerTest}/{@code EmpresaControllerTest},
 * ninguno de estos 3 endpoints toca certificados ni credenciales de Hacienda, así que el
 * bootstrap se limita a Postgres vía Testcontainers.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class UbicacionControllerTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtService jwtService;

    private String crearUsuarioEmpresaYToken() {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();

            Usuario usuario = new Usuario();
            usuario.setNombre("Persona de prueba UbicacionController");
            usuario.setEmail("ubicacion-controller-" + UUID.randomUUID() + "@fractall.test");
            usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa de Prueba UbicacionController S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuario.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            return jwtService.generarToken(usuario.getId(), empresa.getId());
        });
    }

    @Test
    void getProvinciasRetornaLasSieteProvinciasOrdenadasPorCodigo() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();

        mockMvc.perform(get("/catalogo/ubicacion/provincias")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].codigo").value("1"))
                .andExpect(jsonPath("$[0].nombre").value("San José"));
    }

    @Test
    void getCantonesDeUnaProvinciaRetornaSoloLosDeEsaProvincia() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();

        mockMvc.perform(get("/catalogo/ubicacion/provincias/1/cantones")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].provinciaCodigo").value("1"))
                .andExpect(jsonPath("$[0].codigo").value("01"))
                .andExpect(jsonPath("$[0].nombre").value("San José"));
    }

    @Test
    void getDistritosDeUnCantonRetornaSoloLosDeEseCanton() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();

        mockMvc.perform(get("/catalogo/ubicacion/cantones/1/01/distritos")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].provinciaCodigo").value("1"))
                .andExpect(jsonPath("$[0].cantonCodigo").value("01"))
                .andExpect(jsonPath("$[0].nombre").value("CARMEN"));
    }

    @Test
    void getCantonesDeUnaProvinciaInexistenteRetornaListaVacia() throws Exception {
        String accessToken = crearUsuarioEmpresaYToken();

        mockMvc.perform(get("/catalogo/ubicacion/provincias/9/cantones")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getProvinciasSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/catalogo/ubicacion/provincias"))
                .andExpect(status().isUnauthorized());
    }
}
