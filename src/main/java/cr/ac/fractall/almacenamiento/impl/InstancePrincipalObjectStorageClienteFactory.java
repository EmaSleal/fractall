package cr.ac.fractall.almacenamiento.impl;

import org.springframework.stereotype.Component;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;

/**
 * Cliente de producción: Instance Principal de la VM de OCI, sin credenciales estáticas
 * (ADR-051). Mismo código que construía antes {@code OciObjectStorageServiceImpl#obtenerCliente};
 * solo se movió aquí para poder sustituirlo en el contexto de test (ver
 * {@link OciObjectStorageClienteFactory}).
 */
@Component
public class InstancePrincipalObjectStorageClienteFactory implements OciObjectStorageClienteFactory {

    @Override
    public ObjectStorage crear(String region) {
        InstancePrincipalsAuthenticationDetailsProvider proveedor =
                InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        return ObjectStorageClient.builder()
                .region(Region.fromRegionCodeOrId(region))
                .build(proveedor);
    }
}
