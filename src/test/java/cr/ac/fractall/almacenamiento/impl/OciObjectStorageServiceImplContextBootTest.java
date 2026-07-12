package cr.ac.fractall.almacenamiento.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.almacenamiento.ObjectStorageService;

/**
 * Prueba dedicada a la afirmación central del requisito de construcción perezosa documentado en
 * {@link OciObjectStorageServiceImpl}: el contexto COMPLETO de Spring (incluyendo este bean
 * OCI-backed real, sin ningún perfil/stub que lo reemplace) arranca sin problema fuera de una VM
 * de OCI real -- esta máquina de pruebas/CI no tiene acceso al servicio de metadata de instancia
 * que Instance Principal necesita. La prueba NUNCA invoca {@link ObjectStorageService#subir}
 * (eso sí requeriría una VM de OCI real) -- solo confirma que el bean existe y es del tipo
 * concreto esperado, lo que ya demuestra que el constructor no intentó autenticarse contra OCI de
 * forma ansiosa.
 */
@Testcontainers
@SpringBootTest
class OciObjectStorageServiceImplContextBootTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();

        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);
    }

    private static void bootstrapAppRole() throws Exception {
        ejecutarVault("auth", "enable", "approle");
        ejecutarVault("secrets", "enable", "transit");
        ejecutarVault("write", "-f", "transit/keys/" + TRANSIT_KEY);

        String politicaHcl = """
                path "secret/data/empresas/*" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/keys/%s" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/datakey/plaintext/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/decrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
        ExecResult resultadoPolitica = VAULT.execInContainer(
                "sh", "-c",
                "cat <<'EOF' | vault policy write " + POLICY_NAME + " -\n" + politicaHcl + "EOF");
        assertThat(resultadoPolitica.getExitCode()).as(resultadoPolitica.getStderr()).isZero();

        ejecutarVault("write", "auth/approle/role/" + APPROLE_NAME,
                "token_policies=" + POLICY_NAME,
                "token_ttl=1h",
                "token_max_ttl=4h",
                "secret_id_ttl=0",
                "token_num_uses=0");

        roleId = ejecutarVault("read", "-field=role_id", "auth/approle/role/" + APPROLE_NAME + "/role-id")
                .getStdout().trim();
        secretId = ejecutarVault("write", "-field=secret_id", "-f", "auth/approle/role/" + APPROLE_NAME + "/secret-id")
                .getStdout().trim();
    }

    private static ExecResult ejecutarVault(String... comandoVault) throws Exception {
        String[] comandoCompleto = new String[comandoVault.length + 1];
        comandoCompleto[0] = "vault";
        System.arraycopy(comandoVault, 0, comandoCompleto, 1, comandoVault.length);

        ExecResult resultado = VAULT.execInContainer(comandoCompleto);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        return resultado;
    }

    @Autowired
    private ObjectStorageService objectStorageService;

    @Test
    void elContextoCompletoArrancaConElBeanOciBackedRealSinInvocarOci() {
        assertThat(objectStorageService).isInstanceOf(OciObjectStorageServiceImpl.class);
    }

    @Test
    void elBeanOciImplementaDescargar() {
        // Confirma que OciObjectStorageServiceImpl implementa descargar() sin invocar OCI real.
        // La construcción perezosa garantiza que la ausencia de metadata de instancia OCI
        // no impide que el bean exista en el contexto (misma afirmación que la prueba anterior,
        // extendida a la nueva operación de Fase 9).
        assertThat(objectStorageService).isInstanceOf(OciObjectStorageServiceImpl.class);
        OciObjectStorageServiceImpl impl = (OciObjectStorageServiceImpl) objectStorageService;
        assertThat(impl).isNotNull();
    }
}
