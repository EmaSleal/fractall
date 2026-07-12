package cr.ac.fractall.facturacion.servicio;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Genera el XML de Factura Electrónica, lo FIRMA digitalmente (XAdES-BES vía
 * {@link XmlFacturaFirmaService}, sub-tarea "firma digital"), cifra el XML YA FIRMADO (envelope
 * encryption vía la KEK de Transit, sección 6.1, delegado en {@link ComprobanteXmlCifradoUploader}
 * -- ver su javadoc sobre por qué ese cifrado+subida vive en una clase compartida), lo sube a
 * Oracle Object Storage (sección 6.4) y persiste su referencia en
 * {@code comprobante_electronico.xml_comprobante_referencia} -- Fase 8. Justo después de persistir
 * {@code FIRMADO}, invoca {@link ComprobanteHaciendaEnvioService#enviarComprobante} para enviar ese
 * mismo XML a Hacienda en la MISMA request (ver el javadoc de esa clase para las reglas de
 * transición de estado que siguen).
 *
 * <p><b>Por qué se persiste el XML firmado y no el sin firmar:</b> el único propósito de este
 * artefacto es eventualmente enviarse a Hacienda, que exige un {@code <ds:Signature>} real
 * (XAdES-BES) -- persistir la versión sin firmar nunca fue el objetivo final, solo un paso
 * intermedio de una sub-tarea anterior a la firma digital. {@link #generarYPersistirXml} también
 * avanza {@code comprobante.estado} a {@code FIRMADO} en la misma escritura que fija
 * {@code xmlComprobanteReferencia} -- ver el comentario junto a esa asignación más abajo.
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
 *
 * <p>Mismo riesgo aceptado, un caso puntual más: si la empresa no tiene {@code CredencialHacienda}
 * configurada para {@code comprobante.getAmbienteHacienda()}, la llamada a
 * {@link ComprobanteHaciendaEnvioService#enviarComprobante} (línea 130 más abajo) lanza
 * {@link CredencialHaciendaNoEncontradaException} DESPUÉS de que este método ya guardó el
 * comprobante en {@code FIRMADO} -- a diferencia de los otros casos de este párrafo,
 * {@code xml_comprobante_referencia} SÍ queda fijado (el XML se firmó y subió con éxito; solo el
 * envío a Hacienda nunca ocurrió). El comprobante queda en {@code FIRMADO} de forma permanente:
 * {@code ComprobanteHaciendaPollingScheduledJob} solo sondea {@code ENVIADO}, así que nada lo
 * vuelve a recoger automáticamente. No se construye un camino de recuperación para esto (ni el job
 * escanea {@code FIRMADO}, ni se agrega descarga a
 * {@link cr.ac.fractall.almacenamiento.ObjectStorageService} para poder reenviar el XML ya subido
 * -- ver su javadoc sobre por qué esa operación se omite a propósito) por el mismo motivo que el
 * resto de este párrafo: es una ventana evitable (configurar credenciales antes de facturar) y de
 * baja probabilidad, no algo que amerite nueva infraestructura de reintento.
 * {@code FacturaController} sí mapea esta excepción a 503 (no 500) para que quede claro que es un
 * fallo de infraestructura/configuración, no un error del cliente.
 */
@Service
@Slf4j
public class ComprobanteXmlPersistenceService {

    private static final String ESTADO_FIRMADO = "FIRMADO";

    private final XmlFacturaGeneratorService xmlFacturaGeneratorService;
    private final XmlFacturaFirmaService xmlFacturaFirmaService;
    private final ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    public ComprobanteXmlPersistenceService(
            XmlFacturaGeneratorService xmlFacturaGeneratorService,
            XmlFacturaFirmaService xmlFacturaFirmaService,
            ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService) {
        this.xmlFacturaGeneratorService = xmlFacturaGeneratorService;
        this.xmlFacturaFirmaService = xmlFacturaFirmaService;
        this.comprobanteXmlCifradoUploader = comprobanteXmlCifradoUploader;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteHaciendaEnvioService = comprobanteHaciendaEnvioService;
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
     * @throws XmlFacturaFirmaException si la empresa no tiene certificado {@code .p12} cargado o
     *     la firma XAdES-BES falla (ver el javadoc de {@link XmlFacturaFirmaService})
     */
    public void generarYPersistirXml(UUID comprobanteId) {
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findById(comprobanteId)
                .orElseThrow(() -> new ComprobanteElectronicoNoEncontradoException(comprobanteId));

        String xml = xmlFacturaGeneratorService.generarXmlFactura(comprobanteId);
        String xmlFirmado = xmlFacturaFirmaService.firmar(xml, comprobante.getEmpresaId());

        String rutaObjeto = construirRutaObjeto(comprobante.getEmpresaId(), comprobante.getClaveNumerica());
        String referencia = comprobanteXmlCifradoUploader.cifrarYSubir(
                xmlFirmado.getBytes(StandardCharsets.UTF_8), rutaObjeto);

        log.info("XML de comprobante {} subido a Object Storage: {}", comprobanteId, referencia);

        // Ver el javadoc de la clase: save() de SimpleJpaRepository ya es transaccional por sí
        // mismo -- transacción corta y dedicada solo para esta escritura, sin auto-invocación.
        // FIRMADO refleja que, a partir de este punto, el artefacto persistido es el XML REAL
        // firmado (XAdES-BES) que eventualmente se envía a Hacienda -- no el XML sin firmar que
        // este servicio subía antes de esta sub-tarea (secuencia GENERADO -> FIRMADO -> ENVIADO ->
        // ACEPTADO del documento de arquitectura; ver el javadoc de FacturaService#crear sobre por
        // qué GENERADO en sí se asigna prematuramente y ese gap queda fuera de este alcance).
        comprobante.setXmlComprobanteReferencia(referencia);
        comprobante.setEstado(ESTADO_FIRMADO);
        comprobanteElectronicoRepository.save(comprobante);

        // Envío síncrono a Hacienda en la MISMA request, justo después de persistir FIRMADO -- ver
        // el javadoc de ComprobanteHaciendaEnvioService para las reglas de transición de estado
        // que siguen. xmlFirmado se pasa tal cual, todavía en memoria: ObjectStorageService no
        // expone descarga (ver su javadoc), así que este es el único punto donde el XML firmado en
        // claro sigue existiendo fuera de Object Storage.
        comprobanteHaciendaEnvioService.enviarComprobante(xmlFirmado, comprobante);
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
