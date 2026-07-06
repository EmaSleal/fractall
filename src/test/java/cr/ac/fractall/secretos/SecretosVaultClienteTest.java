package cr.ac.fractall.secretos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

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

/**
 * Prueba de integración de punta a punta del cliente de Vault (Fase 3, sección 6 de
 * {@code arquitectura-facturacion-electronica-cr.md}), autenticado vía AppRole -- no vía
 * el token raíz -- exactamente como lo haría el backend real.
 *
 * <p>El bootstrap de {@code scripts/vault-setup-local.sh} (approle, política, llave
 * Transit, rol AppRole) se replica aquí en código contra un {@link VaultContainer} propio
 * en modo dev, en lugar de reutilizar el Vault local de {@code docker compose}, para que
 * la prueba sea autocontenida y reproducible en CI. El motor kv-v2 en {@code secret/} ya
 * viene habilitado por defecto en modo dev -- confirmado al ejecutar el script real contra
 * el Vault local, donde ese paso se saltó por ser idempotente -- así que no se re-habilita
 * aquí.
 */
@Testcontainers
@SpringBootTest
class SecretosVaultClienteTest {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

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

    private static String roleId;
    private static String secretId;

    /**
     * Replica en código, contra el contenedor, el mismo bootstrap que
     * {@code scripts/vault-setup-local.sh} hace contra el Vault local: habilitar approle,
     * habilitar Transit y crear su llave, escribir la política de la sección 6.3, crear el
     * rol AppRole y leer de vuelta su role-id y un secret-id fresco.
     */
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
    private SecretosKvService secretosKvService;

    @Autowired
    private TransitService transitService;

    @Test
    void guardaYLeeUnSecretoKvV2ConElMismoValor() {
        UUID empresaId = UUID.randomUUID();

        secretosKvService.guardarSecreto(empresaId, "certificado/pin", "1234-pin-de-prueba");
        Optional<String> valorLeido = secretosKvService.leerSecreto(empresaId, "certificado/pin");

        assertThat(valorLeido).contains("1234-pin-de-prueba");
    }

    @Test
    void generarDekYLuegoDescifrarlaDevuelveElMismoTextoPlano() {
        TransitService.Dek dek = transitService.generarDek();

        assertThat(dek.plaintext()).isNotEmpty();
        assertThat(dek.cifrado()).isNotEmpty();

        byte[] plaintextRecuperado = transitService.descifrarDek(dek.cifrado());

        assertThat(plaintextRecuperado).isEqualTo(dek.plaintext());
        // Confirma también que el blob persistido es el string "vault:v1:..." de Transit,
        // no el texto plano -- la propiedad central de la sección 6.1: la DEK en texto
        // plano nunca debe ser lo que se guarda.
        String cifradoComoTexto = new String(dek.cifrado(), StandardCharsets.UTF_8);
        assertThat(cifradoComoTexto).startsWith("vault:v1:");
    }
}
