package cr.ac.fractall.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cr.ac.fractall.seguridad.servicio.JwtService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Criterio de salida de la Fase 2 (ver {@code plan-fases-release-1.md}): un endpoint
 * protegido rechaza solicitudes sin token y acepta un JWT construido manualmente.
 *
 * <p>El endpoint de prueba ({@code GET /api/ping}, ver {@link PingController} al final
 * de este archivo) vive únicamente en este árbol de pruebas — no existe ningún
 * controlador de negocio real todavía (eso es de fases posteriores), así que no se
 * agrega un controlador placeholder permanente al código de producción.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

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
    private JwtService jwtService;

    @Test
    void rechazaSolicitudSinToken() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aceptaJwtConstruidoManualmenteYLimpiaTenantContextAlFinalizar() throws Exception {
        String token = jwtService.generarToken(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Confirma que el `finally` de JwtTenantFilter realmente limpió el ThreadLocal
        // tras completarse el request.
        assertThat(TenantContext.get()).isNull();
    }
}

/**
 * Controlador de prueba, deliberadamente de nivel superior (no anidado) y sin ser
 * {@code public}, en el mismo archivo que {@link SecurityConfigTest} — vive únicamente
 * en {@code src/test/java}, cargado por el component-scan normal de
 * {@code cr.ac.fractall} igual que cualquier otro bean del árbol de pruebas, sin
 * necesidad de un {@code @TestConfiguration}/{@code @Bean} adicional (que produciría un
 * segundo bean para la misma clase y, con eso, un "Ambiguous mapping" al arrancar).
 */
@RestController
class PingController {
    @GetMapping("/api/ping")
    String ping() {
        return "pong";
    }
}
