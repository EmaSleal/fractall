package cr.ac.fractall.secretos;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

/**
 * Motor KV v2 de Vault, namespacing explícito por empresa (sección 6.2 de
 * {@code arquitectura-facturacion-electronica-cr.md}):
 *
 * <pre>
 * secret/data/empresas/{empresa_id}/certificado/pin
 * secret/data/empresas/{empresa_id}/hacienda/sandbox/password
 * secret/data/empresas/{empresa_id}/hacienda/produccion/password
 * </pre>
 *
 * <p>Estas tres rutas (y las que se agreguen después) son literalmente el valor que se
 * guarda en columnas puntero como {@code empresa.certificado_referencia} y
 * {@code credencial_hacienda.credencial_referencia} -- de ahí que este servicio sea
 * genérico sobre la subruta en lugar de exponer un método dedicado por cada una: el
 * conjunto de subrutas va a crecer en fases posteriores sin que este servicio deba
 * cambiar.
 *
 * <p>El aislamiento por tenant aquí es responsabilidad exclusiva del llamador: el propio
 * código, anclado al {@code TenantContext} activo, nunca debe construir una ruta con un
 * {@code empresaId} distinto al de la sesión (sección 6.3).
 */
@Service
public class SecretosKvService {

    private static final String KV_MOUNT = "secret";
    private static final String CAMPO_VALOR = "valor";

    private final VaultKeyValueOperations kv;

    public SecretosKvService(VaultOperations vaultOperations) {
        this.kv = vaultOperations.opsForKeyValue(KV_MOUNT, KeyValueBackend.KV_2);
    }

    public void guardarSecreto(UUID empresaId, String subruta, String valor) {
        kv.put(rutaCompleta(empresaId, subruta), Map.of(CAMPO_VALOR, valor));
    }

    public Optional<String> leerSecreto(UUID empresaId, String subruta) {
        VaultResponse respuesta = kv.get(rutaCompleta(empresaId, subruta));
        if (respuesta == null || !respuesta.hasData()) {
            return Optional.empty();
        }
        Object valor = respuesta.getRequiredData().get(CAMPO_VALOR);
        return Optional.ofNullable(valor).map(Object::toString);
    }

    private static String rutaCompleta(UUID empresaId, String subruta) {
        return "empresas/" + empresaId + "/" + subruta;
    }
}
