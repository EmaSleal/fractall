package cr.ac.fractall.facturacion.servicio;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;

/**
 * Cifra (envelope encryption vía una DEK de {@link TransitService}) y sube a
 * {@link ObjectStorageService} un XML ya en texto claro, usando siempre el mismo layout
 * auto-descriptivo ({@link ComprobanteXmlBlobFormat}) -- factorizado aquí porque tanto
 * {@link ComprobanteXmlPersistenceService#generarYPersistirXml} (el XML YA FIRMADO) como
 * {@link ComprobanteHaciendaEnvioService} (el XML de RESPUESTA que devuelve Hacienda, cuando
 * viene) necesitan exactamente la misma danza DEK-por-operación + AES-GCM + serialización, y
 * duplicarla en dos clases distintas invitaría a que una de las dos copias se desincronizara de
 * la otra silenciosamente.
 */
@Component
class ComprobanteXmlCifradoUploader {

    private final TransitService transitService;
    private final ObjectStorageService objectStorageService;

    ComprobanteXmlCifradoUploader(TransitService transitService, ObjectStorageService objectStorageService) {
        this.transitService = transitService;
        this.objectStorageService = objectStorageService;
    }

    /**
     * @param xmlClaro bytes en claro (UTF-8) a cifrar y subir -- el llamador nunca debe pasar
     *     contenido ya cifrado.
     * @param rutaObjeto ruta/clave del objeto dentro del bucket configurado (ver
     *     {@link ComprobanteXmlPersistenceService#construirRutaObjeto} y
     *     {@link ComprobanteHaciendaEnvioService} para las dos convenciones de nombres usadas).
     * @return la referencia devuelta por {@link ObjectStorageService#subir}.
     */
    String cifrarYSubir(byte[] xmlClaro, String rutaObjeto) {
        TransitService.Dek dek = transitService.generarDek();
        byte[] xmlCifrado;
        try {
            xmlCifrado = EnvelopeCipher.cifrar(dek.plaintext(), xmlClaro);
        } finally {
            // Descarte inmediato de la DEK en texto plano -- solo dek.cifrado() se sube (envuelto
            // dentro del blob), nunca el plaintext. Ver ComprobanteXmlPersistenceService.
            Arrays.fill(dek.plaintext(), (byte) 0);
        }

        byte[] blob = ComprobanteXmlBlobFormat.serializar(dek.cifrado(), xmlCifrado);
        return objectStorageService.subir(blob, rutaObjeto);
    }
}
