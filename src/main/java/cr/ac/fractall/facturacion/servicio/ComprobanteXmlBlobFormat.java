package cr.ac.fractall.facturacion.servicio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Formato binario auto-descriptivo del objeto subido a Oracle Object Storage para un XML de
 * comprobante cifrado (Fase 8, sección 6.4 de {@code arquitectura-facturacion-electronica-cr.md}
 * y decisión de diseño de esta sub-tarea): un único objeto que trae todo lo necesario para
 * descifrarlo más adelante (firma XAdES-BES y envío a Hacienda son fases futuras separadas), sin
 * necesitar una columna nueva en {@code comprobante_electronico} para la DEK envuelta --
 * {@code xml_comprobante_referencia} solo guarda la ruta del objeto (ver
 * {@link ComprobanteXmlPersistenceService}).
 *
 * <p><b>Layout exacto</b> (todos los enteros en big-endian, orden por defecto de
 * {@link ByteBuffer}):
 *
 * <pre>
 * [4 bytes] longitud de la DEK envuelta, como int32 big-endian
 * [N bytes] DEK envuelta (TransitService.Dek#cifrado() -- el string "vault:v1:..." de Vault
 *           Transit, como bytes UTF-8)
 * [M bytes] XML cifrado (salida de EnvelopeCipher#cifrar -- ya trae su propio IV de GCM como
 *           prefijo de 12 bytes, seguido del ciphertext+tag; ver el javadoc de EnvelopeCipher)
 * </pre>
 *
 * <p>Solo se provee {@link #serializar} -- ningún código de este release necesita releer el
 * objeto (ver el javadoc de {@code ObjectStorageService} sobre por qué no existe un método de
 * descarga todavía). Un futuro deserializador es trivial a partir de este layout: leer los
 * primeros 4 bytes como {@code int} big-endian para obtener {@code N}, tomar los siguientes
 * {@code N} bytes como la DEK envuelta, y el resto como el XML cifrado.
 */
final class ComprobanteXmlBlobFormat {

    private static final int PREFIJO_LONGITUD_BYTES = 4;

    private ComprobanteXmlBlobFormat() {
    }

    static byte[] serializar(byte[] dekEnvuelta, byte[] xmlCifrado) {
        ByteBuffer buffer = ByteBuffer
                .allocate(PREFIJO_LONGITUD_BYTES + dekEnvuelta.length + xmlCifrado.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(dekEnvuelta.length);
        buffer.put(dekEnvuelta);
        buffer.put(xmlCifrado);
        return buffer.array();
    }
}
