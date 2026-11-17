package cr.ac.fractall.almacenamiento.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

/**
 * Sustituye, en TODO contexto de Spring de los tests, el cliente del SDK de OCI por uno en memoria
 * (T-007, P-017). Es una {@code @Configuration} normal, no {@code @TestConfiguration}, a propósito:
 * vive en {@code src/test} bajo {@code cr.ac.fractall}, así que el escaneo de componentes de
 * {@code FractallApplication} la recoge en cada {@code @SpringBootTest} sin que cada clase tenga
 * que importarla, y un test nuevo no puede olvidarse de ella.
 *
 * <p>Solo se sustituye {@link OciObjectStorageClienteFactory}: {@link OciObjectStorageServiceImpl}
 * sigue siendo el bean real de {@code ObjectStorageService}, con su construcción perezosa y su
 * armado de peticiones. Ningún test construye el proveedor de Instance Principal, así que nadie
 * llega al endpoint de metadatos de OCI (169.254.169.254) ni a Object Storage real.
 *
 * <p>El cliente falso implementa solo {@code putObject} y {@code getObject}, las dos operaciones
 * que usa {@link OciObjectStorageServiceImpl}. Un objeto inexistente responde 404 como el servicio
 * real. Cualquier otra operación devuelve {@code null} (mock de Mockito); si algún día se usa,
 * hay que agregarla aquí.
 */
@Configuration
public class ObjectStorageEnMemoriaTestConfig {

    @Bean
    @Primary
    OciObjectStorageClienteFactory objectStorageEnMemoriaClienteFactory() {
        return region -> crearClienteEnMemoria(new ConcurrentHashMap<>());
    }

    static ObjectStorage crearClienteEnMemoria(Map<String, byte[]> objetos) {
        ObjectStorage cliente = mock(ObjectStorage.class);

        when(cliente.putObject(any(PutObjectRequest.class))).thenAnswer(invocacion -> {
            PutObjectRequest request = invocacion.getArgument(0);
            objetos.put(clave(request.getNamespaceName(), request.getBucketName(), request.getObjectName()),
                    leer(request));
            return PutObjectResponse.builder().build();
        });

        when(cliente.getObject(any(GetObjectRequest.class))).thenAnswer(invocacion -> {
            GetObjectRequest request = invocacion.getArgument(0);
            byte[] contenido = objetos.get(
                    clave(request.getNamespaceName(), request.getBucketName(), request.getObjectName()));
            if (contenido == null) {
                throw new BmcException(404, "ObjectNotFound",
                        "Objeto inexistente en el almacenamiento en memoria: " + request.getObjectName(),
                        "test-en-memoria");
            }
            return GetObjectResponse.builder().inputStream(new ByteArrayInputStream(contenido)).build();
        });

        return cliente;
    }

    private static String clave(String namespace, String bucket, String objeto) {
        return namespace + "/" + bucket + "/" + objeto;
    }

    private static byte[] leer(PutObjectRequest request) {
        try {
            return request.getPutObjectBody().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
