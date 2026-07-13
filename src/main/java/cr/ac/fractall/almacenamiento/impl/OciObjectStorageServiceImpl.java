package cr.ac.fractall.almacenamiento.impl;

import java.io.ByteArrayInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementación real de {@link ObjectStorageService} contra Oracle Object Storage (Fase 8,
 * sección 6.4 de {@code arquitectura-facturacion-electronica-cr.md}), autenticada por
 * <b>Instance Principal</b> ({@link InstancePrincipalsAuthenticationDetailsProvider}) -- sin
 * ningún par de credenciales estáticas (Access/Secret Key) que custodiar.
 *
 * <p><b>Construcción perezosa del cliente, no negociable:</b> Instance Principal solo funciona
 * ejecutando sobre una VM de OCI real -- la autenticación consulta el servicio de metadata de la
 * instancia ({@code 169.254.169.254}), inalcanzable en esta máquina de desarrollo local y en
 * cualquier entorno de CI. Si {@link #obtenerCliente()} construyera el
 * {@link InstancePrincipalsAuthenticationDetailsProvider}/{@link ObjectStorageClient} en el
 * constructor o vía {@code @PostConstruct}, ESE intento de red fallaría durante el arranque del
 * contexto de Spring, y CUALQUIER {@code @SpringBootTest} de este proyecto (incluyendo pruebas que
 * nunca tocan facturación) dejaría de arrancar. En cambio, el cliente se construye la primera vez
 * que {@link #subir} se invoca de verdad -- ningún test de este proyecto invoca {@code subir()}
 * contra la implementación real (ver el javadoc de
 * {@code cr.ac.fractall.facturacion.servicio.ComprobanteXmlPersistenceService} y su prueba: el
 * colaborador se reemplaza con {@code @MockitoBean}), así que esa ruta nunca se ejercita fuera de
 * una VM de OCI real.
 *
 * <p><b>Desviación deliberada de la convención de pruebas de este proyecto:</b> Vault (Fase 3) sí
 * se prueba contra una instancia real vía Testcontainers porque existe una imagen de contenedor
 * oficial ejecutable localmente. Oracle Object Storage no tiene un equivalente de contenedor local
 * disponible en este entorno, y llamar al OCI real desde una prueba automatizada no es viable
 * (requeriría una VM de OCI real, credenciales, y acceso de red saliente en CI) -- por eso el
 * colaborador de este servicio se mockea en las pruebas de {@code ComprobanteXmlPersistenceService}
 * en lugar de ejercitarse de punta a punta, a diferencia de {@code TransitService}/
 * {@code SecretosKvService}. Esto es una excepción intencional, documentada aquí para que un
 * lector futuro no la confunda con un descuido.
 */
@Service
@Slf4j
public class OciObjectStorageServiceImpl implements ObjectStorageService {

    private final String bucket;
    private final String namespace;
    private final String region;

    private final Object candadoConstruccion = new Object();
    private volatile ObjectStorageClient cliente;

    // bucket/region llevan su default explícito EN el propio @Value, no solo en application.yaml
    // -- src/test/resources/application.yaml es un archivo de propiedades de prueba completo y
    // SEPARADO (no un complemento del principal, ver el javadoc de HaciendaComprobanteApiServiceImpl
    // para el precedente exacto de este patrón); sin el default acá también, CUALQUIER
    // @SpringBootTest fallaría con PlaceholderResolutionException en cuanto este bean entrara al
    // contexto. namespace, en cambio, NO lleva default ni aquí ni en el application.yaml principal
    // (mismo principio que VAULT_ROLE_ID/RESEND_API_KEY) -- en pruebas su placeholder vive en
    // src/test/resources/application.yaml, mismo patrón que RESEND_API_KEY en ese archivo.
    public OciObjectStorageServiceImpl(
            @Value("${application.almacenamiento.oci.bucket:fractall-comprobantes}") String bucket,
            @Value("${application.almacenamiento.oci.namespace}") String namespace,
            @Value("${application.almacenamiento.oci.region:mx-queretaro-1}") String region) {
        this.bucket = bucket;
        this.namespace = namespace;
        this.region = region;
    }

    @Override
    public byte[] descargar(String rutaObjeto) {
        log.info("Descargando objeto de Oracle Object Storage: {}", rutaObjeto);

        GetObjectRequest request = GetObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(rutaObjeto)
                .build();

        try {
            return obtenerCliente().getObject(request).getInputStream().readAllBytes();
        } catch (java.io.IOException excepcion) {
            throw new IllegalStateException("No se pudo leer el contenido del objeto OCI: " + rutaObjeto, excepcion);
        }
    }

    @Override
    public String subir(byte[] contenido, String rutaObjeto) {
        log.info("Subiendo objeto a Oracle Object Storage: {}", rutaObjeto);

        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(rutaObjeto)
                .contentLength((long) contenido.length)
                .putObjectBody(new ByteArrayInputStream(contenido))
                .build();

        obtenerCliente().putObject(request);
        return rutaObjeto;
    }

    /**
     * Double-checked locking clásico -- {@link #cliente} es {@code volatile} para publicar de
     * forma segura la instancia ya construida a otros hilos sin sincronizar cada llamada a
     * {@link #subir}, solo la primera. Ver el javadoc de la clase sobre por qué esta construcción
     * NO puede ocurrir antes de la primera invocación real.
     */
    private ObjectStorageClient obtenerCliente() {
        ObjectStorageClient resultado = cliente;
        if (resultado == null) {
            synchronized (candadoConstruccion) {
                resultado = cliente;
                if (resultado == null) {
                    InstancePrincipalsAuthenticationDetailsProvider proveedor =
                            InstancePrincipalsAuthenticationDetailsProvider.builder().build();
                    resultado = ObjectStorageClient.builder()
                            .region(Region.fromRegionCodeOrId(region))
                            .build(proveedor);
                    cliente = resultado;
                }
            }
        }
        return resultado;
    }
}
