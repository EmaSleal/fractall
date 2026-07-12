package cr.ac.fractall.facturacion.servicio;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;

/**
 * Inverso exacto de {@link ComprobanteXmlCifradoUploader}: descarga el blob cifrado de
 * {@link ObjectStorageService}, lo deserializa con {@link ComprobanteXmlBlobFormat},
 * descifra la DEK envuelta vía {@link TransitService} y descifra el XML con AES-GCM
 * ({@link EnvelopeCipher}). Retorna el contenido en texto claro.
 *
 * <p>Factorizado como clase separada (y no como método adicional en el Uploader) porque la
 * operación de lectura/descifrado es la contraparte simétrica de escritura/cifrado: el
 * nombre "Uploader" sería una mentira si contuviera lógica de descarga, y el read/write
 * split es explícito en el diseño de Fase 9.
 */
@Component
class ComprobanteXmlCifradoDescargador {

    private final TransitService transitService;
    private final ObjectStorageService objectStorageService;

    ComprobanteXmlCifradoDescargador(TransitService transitService, ObjectStorageService objectStorageService) {
        this.transitService = transitService;
        this.objectStorageService = objectStorageService;
    }

    /**
     * @param rutaObjeto ruta/clave del objeto en OCI (mismo valor que devolvió
     *     {@link ComprobanteXmlCifradoUploader#cifrarYSubir} al subir el XML).
     * @return bytes del XML en texto claro -- nunca el blob cifrado (FR-04).
     */
    byte[] descargarYDescifrar(String rutaObjeto) {
        byte[] blob = objectStorageService.descargar(rutaObjeto);
        ComprobanteXmlBlobFormat.Contenido contenido = ComprobanteXmlBlobFormat.deserializar(blob);
        byte[] dekPlano = transitService.descifrarDek(contenido.dekEnvuelta());
        try {
            return EnvelopeCipher.descifrar(dekPlano, contenido.xmlCifrado());
        } finally {
            // Descarte inmediato de la DEK en texto plano -- simetría con ComprobanteXmlCifradoUploader.
            Arrays.fill(dekPlano, (byte) 0);
        }
    }
}
