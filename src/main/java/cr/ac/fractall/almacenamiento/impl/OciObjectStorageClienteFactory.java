package cr.ac.fractall.almacenamiento.impl;

import com.oracle.bmc.objectstorage.ObjectStorage;

/**
 * Crea el cliente del SDK de OCI que usa {@link OciObjectStorageServiceImpl}. Es el único punto
 * donde se elige cómo se autentica ese cliente.
 *
 * <p>En producción la implementación es {@link InstancePrincipalObjectStorageClienteFactory}
 * (Instance Principal, ADR-051). Existe como punto de extensión para que el contexto de test
 * pueda sustituir el cliente por uno en memoria: construir el proveedor de Instance Principal
 * fuera de una VM de OCI dispara el chequeo del endpoint de metadatos (IMDS) del SDK, que se
 * queda reintentando conexiones a 169.254.169.254 durante minutos (P-017).
 *
 * <p>{@link OciObjectStorageServiceImpl} la invoca de forma perezosa, en la primera operación
 * real, y una sola vez.
 */
@FunctionalInterface
public interface OciObjectStorageClienteFactory {

    ObjectStorage crear(String region);
}
