package cr.ac.fractall.facturacion.servicio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;
import lombok.extern.slf4j.Slf4j;

/**
 * Genera el XML de Factura Electrónica, lo cifra (envelope encryption vía la KEK de Transit,
 * sección 6.1), lo sube a Oracle Object Storage (sección 6.4) y persiste su referencia en
 * {@code comprobante_electronico.xml_comprobante_referencia} -- Fase 8, sub-tarea "conectar el
 * generador de XML al flujo real de creación de factura".
 *
 * <p><b>Por qué esto NO es parte de {@code FacturaService#crear}:</b> {@code crear()} corre en una
 * única transacción que sostiene un bloqueo pesimista de fila sobre {@code contador_consecutivo}
 * (Fase 7, ver su javadoc) -- esa transacción debe mantenerse lo más corta posible. Generar el XML,
 * llamar a Vault Transit y subir a Object Storage implica trabajo de red no trivial (varios
 * round-trips) que, de correr dentro de esa misma transacción, extendería el bloqueo de fila
 * mucho más allá de lo que ese diseño tolera. Por eso este servicio es un paso SEPARADO, invocado
 * por {@code FacturaController} DESPUÉS de que la transacción de {@code crear()} ya hizo commit
 * (ver su javadoc) -- {@link #generarYPersistirXml} deliberadamente NO lleva {@code @Transactional}
 * a nivel de método.
 *
 * <p><b>Por qué la escritura final tampoco usa un {@code @Transactional} propio explícito (y por
 * qué eso no contradice "transacción corta y dedicada para el UPDATE final"):</b> un método
 * {@code @Transactional} de esta misma clase, invocado internamente desde
 * {@link #generarYPersistirXml}, caería en la misma auto-invocación que ya mordió a este
 * codebase en la Fase 7 (ver la nota de esa fase) -- el proxy de Spring que aplica
 * {@code @Transactional} nunca se activa en una llamada {@code this.metodo(...)} dentro de la
 * misma instancia. En vez de introducir ese riesgo, la escritura final (mutar el campo y llamar
 * {@link ComprobanteElectronicoRepository#save}) se apoya en que {@code SimpleJpaRepository} ya
 * es {@code @Transactional} por sí mismo -- {@code save()} corre en su propia transacción corta y
 * dedicada, a través del proxy real del repositorio (un bean distinto de este servicio), sin
 * necesidad de que esta clase declare ninguna transacción propia.
 *
 * <p><b>Riesgo de fallo parcial, documentado y aceptado (no se corrige aquí):</b> si la generación
 * del XML, el cifrado o la subida fallan DESPUÉS de que la transacción de {@code crear()} ya hizo
 * commit, la factura y el comprobante ya existen en la base de datos con
 * {@code xml_comprobante_referencia} en {@code null} -- un estado parcial real. Este servicio NO
 * implementa reintento ni un job de reconciliación para ese caso (mismo principio ya aplicado en
 * las Fases 6/7: documentar un riesgo de baja probabilidad en vez de construir una saga completa
 * antes de que el negocio la necesite); en su lugar, la excepción se deja propagar sin capturar,
 * para que el llamador HTTP la vea como un error real (nunca se traga en silencio).
 */
@Service
@Slf4j
public class ComprobanteXmlPersistenceService {

    private final XmlFacturaGeneratorService xmlFacturaGeneratorService;
    private final TransitService transitService;
    private final ObjectStorageService objectStorageService;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;

    public ComprobanteXmlPersistenceService(
            XmlFacturaGeneratorService xmlFacturaGeneratorService,
            TransitService transitService,
            ObjectStorageService objectStorageService,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository) {
        this.xmlFacturaGeneratorService = xmlFacturaGeneratorService;
        this.transitService = transitService;
        this.objectStorageService = objectStorageService;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
    }

    /**
     * Debe invocarse DESPUÉS de que la transacción de {@code FacturaService#crear} ya hizo commit
     * -- ver el javadoc de la clase. No lleva {@code @Transactional}: todo el trabajo de red
     * (generación de XML -- que sí hace lecturas propias vía repositorio --, Vault Transit, Object
     * Storage) corre fuera de cualquier transacción larga.
     *
     * @param comprobanteId id de un {@code ComprobanteElectronico} ya persistido, del tenant
     *     actual ({@link cr.ac.fractall.tenant.TenantContext})
     * @throws ComprobanteElectronicoNoEncontradoException si no existe un comprobante con ese id
     *     para el tenant actual
     * @throws XmlFacturaInvalidoException si el XML generado no cumple el XSD (bug interno del
     *     generador, ver su javadoc)
     */
    public void generarYPersistirXml(UUID comprobanteId) {
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

        String xml = xmlFacturaGeneratorService.generarXmlFactura(comprobanteId);

        TransitService.Dek dek = transitService.generarDek();
        byte[] xmlCifrado;
        try {
            xmlCifrado = EnvelopeCipher.cifrar(dek.plaintext(), xml.getBytes(StandardCharsets.UTF_8));
        } finally {
            // Descarte inmediato de la DEK en texto plano (sección 6.1) -- solo dek.cifrado() se
            // sube (envuelto dentro del blob, ver ComprobanteXmlBlobFormat), nunca el plaintext.
            Arrays.fill(dek.plaintext(), (byte) 0);
        }

        byte[] blob = ComprobanteXmlBlobFormat.serializar(dek.cifrado(), xmlCifrado);
        String rutaObjeto = construirRutaObjeto(comprobante.getEmpresaId(), comprobante.getClaveNumerica());
        String referencia = objectStorageService.subir(blob, rutaObjeto);

        log.info("XML de comprobante {} subido a Object Storage: {}", comprobanteId, referencia);

        // Ver el javadoc de la clase: save() de SimpleJpaRepository ya es transaccional por sí
        // mismo -- transacción corta y dedicada solo para esta escritura, sin auto-invocación.
        comprobante.setXmlComprobanteReferencia(referencia);
        comprobanteElectronicoRepository.save(comprobante);
    }

    /**
     * {@code empresas/{empresaId}/comprobantes/{claveNumerica}.xml.enc} -- mismo principio de
     * ruta legible y determinística ya usado para {@code certificado_referencia}
     * (Fase 5, ver {@code EmpresaService}), con la clave numérica (única por comprobante, 50
     * dígitos) como componente final para que la ruta nunca choque entre dos comprobantes de la
     * misma empresa.
     */
    private static String construirRutaObjeto(UUID empresaId, String claveNumerica) {
        return "empresas/" + empresaId + "/comprobantes/" + claveNumerica + ".xml.enc";
    }
}
