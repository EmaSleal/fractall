package cr.ac.fractall.secretos;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.config.AbstractVaultConfiguration;

/**
 * Configuración del cliente de Vault: autenticación AppRole contra el motor {@code approle}
 * ya bootstrapeado por {@code scripts/vault-setup-local.sh} (sección 6.3 de
 * {@code arquitectura-facturacion-electronica-cr.md} — una sola identidad de aplicación,
 * nunca un token de Vault por tenant).
 *
 * <p>{@code VAULT_ROLE_ID} y {@code VAULT_SECRET_ID} no tienen valor por defecto a
 * propósito: a diferencia del secreto JWT de desarrollo, no existe un fallback razonable
 * para estos — deben venir siempre de la salida real del script de bootstrap, nunca de un
 * valor adivinable en el código fuente.
 *
 * <p>{@code AbstractVaultConfiguration} expone {@code secretLeaseContainer()} y
 * {@code certificateContainer()} como beans {@code SmartLifecycle}: Spring los arranca
 * durante el refresh del contexto sin importar si algo los usa, así que en la práctica
 * esta clase (y por tanto una autenticación AppRole real contra Vault) queda activa desde
 * que el contexto de Spring arranca -- no hay forma de diferirla con {@code @Lazy}. Por
 * eso cualquier arranque completo de la aplicación (incluyendo {@code @SpringBootTest})
 * requiere un Vault real, ya bootstrapeado, alcanzable -- exactamente igual que ya requiere
 * Postgres.
 */
@Configuration
public class VaultConfig extends AbstractVaultConfiguration {

    private final String vaultAddr;
    private final String roleId;
    private final String secretId;

    public VaultConfig(
            @Value("${application.vault.addr}") String vaultAddr,
            @Value("${application.vault.role-id}") String roleId,
            @Value("${application.vault.secret-id}") String secretId) {
        this.vaultAddr = vaultAddr;
        this.roleId = roleId;
        this.secretId = secretId;
    }

    @Override
    public VaultEndpoint vaultEndpoint() {
        return VaultEndpoint.from(URI.create(vaultAddr));
    }

    @Override
    public ClientAuthentication clientAuthentication() {
        AppRoleAuthenticationOptions options = AppRoleAuthenticationOptions.builder()
                .roleId(AppRoleAuthenticationOptions.RoleId.provided(roleId))
                .secretId(AppRoleAuthenticationOptions.SecretId.provided(secretId))
                .build();
        // vaultClient() (protegido, no deprecado) en lugar de restOperations(): el
        // constructor de AppRoleAuthentication que recibe RestOperations está deprecado
        // desde la 4.1, y la propia superclase ya expone esta alternativa.
        return new AppRoleAuthentication(options, vaultClient());
    }
}
