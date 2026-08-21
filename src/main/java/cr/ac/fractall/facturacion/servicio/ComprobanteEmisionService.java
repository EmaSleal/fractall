package cr.ac.fractall.facturacion.servicio;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;

/**
 * Extracción type-parameterizada de {@code FacturaService.java:469-487} y {@code :301-321}
 * (Release 2 / Fase B, ver el diseño D-B) -- fuente única de la asignación de consecutivo+clave
 * numérica+creación de {@code ComprobanteElectronico}, y del reenvío a Hacienda, compartida por
 * Factura Electrónica y (a partir de la Fase 3 de este release) Nota de Crédito/Débito.
 *
 * <p><b>Por qué {@link #registrarComprobante} lleva {@code @Transactional(propagation = MANDATORY)}
 * y no el {@code @Transactional} por defecto (REQUIRED):</b> el invariante de la Fase 7 (ver el
 * javadoc histórico de {@code FacturaService#crear}) es que el reclamo del consecutivo
 * ({@code ConsecutivoService#siguienteConsecutivo}, con su bloqueo pesimista de fila) debe correr
 * DENTRO de la misma transacción que ya escribió la factura y sus líneas -- así un
 * {@code ROLLBACK} posterior revierte también el incremento del consecutivo, sin dejar huecos. Con
 * propagación REQUIRED, un llamador que se equivoque y invoque este método sin una transacción
 * activa abriría silenciosamente una transacción nueva y corta, rompiendo ese invariante sin
 * ningún error visible. MANDATORY falla de forma ruidosa
 * ({@link org.springframework.transaction.IllegalTransactionStateException}) en ese caso, en vez
 * de degradar el invariante en silencio.
 *
 * <p><b>Por qué {@link #procesarXmlYEnvio} y {@link #reenviar} NO llevan {@code @Transactional}:</b>
 * ambos disparan trabajo de red (generación de XML, Vault Transit, Object Storage, llamada a
 * Hacienda) que nunca debe correr dentro de una transacción larga -- mismo principio ya documentado
 * en {@code ComprobanteXmlPersistenceService} y en el {@code FacturaService#reenviar} original.
 */
@Service
public class ComprobanteEmisionService {

    private static final String ESTADO_GENERADO = "GENERADO";
    private static final Set<String> ESTADOS_REENVIABLES = Set.of("FIRMADO", "RECHAZADO", "ERROR");

    private final ConsecutivoService consecutivoService;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;
    private final ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;
    private final ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;

    public ComprobanteEmisionService(
            ConsecutivoService consecutivoService,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteXmlPersistenceService comprobanteXmlPersistenceService,
            ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador,
            ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService) {
        this.consecutivoService = consecutivoService;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteXmlPersistenceService = comprobanteXmlPersistenceService;
        this.comprobanteXmlCifradoDescargador = comprobanteXmlCifradoDescargador;
        this.comprobanteHaciendaEnvioService = comprobanteHaciendaEnvioService;
    }

    /**
     * Cuerpo verbatim de {@code FacturaService.java:469-487}, con la constante {@code "01"}
     * reemplazada por {@code perfil.getCodigo()} -- ver el javadoc de la clase sobre el porqué de
     * {@code MANDATORY}. {@code empresa} y {@code ahoraUtc} se reciben como parámetros (no se
     * re-derivan) porque el llamador ya los tiene resueltos, y {@code ahoraUtc} debe ser el MISMO
     * instante que ya alimentó {@code factura.createDate} -- re-derivarlo aquí podría producir un
     * segundo instante distinto entre la fecha de la factura y la fecha embebida en la clave
     * numérica.
     *
     * @param factura ya persistida (con id asignado) en la transacción activa
     * @param perfil determina el código de tipo de comprobante usado tanto para el consecutivo
     *     scoped como para el prefijo embebido en la clave numérica
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ComprobanteElectronico registrarComprobante(
            Factura factura, TipoComprobantePerfil perfil, Empresa empresa, ZonedDateTime ahoraUtc) {
        long numeroConsecutivo = consecutivoService.siguienteConsecutivo(
                empresa.getId(), empresa.getAmbienteHacienda(), perfil.getCodigo());

        String consecutivoFormateado = ClaveNumericaGenerator.formatearConsecutivo(
                numeroConsecutivo, perfil.getCodigo());
        String claveNumerica = ClaveNumericaGenerator.generar(
                empresa.getNumeroIdentificacion(), numeroConsecutivo, perfil.getCodigo(), ahoraUtc);

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda(empresa.getAmbienteHacienda());
        comprobante.setTipoComprobante(perfil.getCodigo());
        comprobante.setConsecutivo(consecutivoFormateado);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado(ESTADO_GENERADO);
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(ahoraUtc.toLocalDateTime());
        comprobanteElectronicoRepository.saveAndFlush(comprobante);

        return comprobante;
    }

    /**
     * POST-COMMIT. Delegación delgada a {@link ComprobanteXmlPersistenceService#generarYPersistirXml}
     * -- debe invocarse DESPUÉS de que la transacción que llamó a {@link #registrarComprobante} ya
     * hizo commit (ver el javadoc de {@code ComprobanteXmlPersistenceService}). Deliberadamente sin
     * {@code @Transactional} -- ver el javadoc de la clase.
     */
    public void procesarXmlYEnvio(UUID comprobanteId) {
        comprobanteXmlPersistenceService.generarYPersistirXml(comprobanteId);
    }

    /**
     * Cuerpo verbatim de {@code FacturaService.java:301-321} -- reenvía a Hacienda un comprobante
     * atascado en un estado terminal/inalcanzable (FIRMADO, RECHAZADO, ERROR). A diferencia del
     * original, devuelve la entidad {@link ComprobanteElectronico}, no {@code FacturaResponse} --
     * esa proyección sigue siendo responsabilidad de {@code FacturaService}, que envuelve esta
     * llamada y hace {@code obtener(facturaId)} después.
     *
     * @throws FacturaNoEncontradaException si no existe comprobante para {@code facturaId}
     * @throws ComprobanteNoReenviableException si el estado no es FIRMADO/RECHAZADO/ERROR, o si
     *     {@code xmlComprobanteReferencia} es null
     */
    public ComprobanteElectronico reenviar(UUID facturaId) {
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findByFacturaId(facturaId)
                .orElseThrow(() -> new FacturaNoEncontradaException(facturaId));

        if (!ESTADOS_REENVIABLES.contains(comprobante.getEstado())) {
            throw new ComprobanteNoReenviableException(facturaId, comprobante.getEstado());
        }
        if (comprobante.getXmlComprobanteReferencia() == null) {
            throw new ComprobanteNoReenviableException(facturaId, comprobante.getEstado());
        }

        byte[] xmlBytes = comprobanteXmlCifradoDescargador.descargarYDescifrar(
                comprobante.getXmlComprobanteReferencia());
        String xmlFirmado = new String(xmlBytes, StandardCharsets.UTF_8);

        comprobante.setIntentosEnvio(0);
        comprobanteElectronicoRepository.save(comprobante);

        comprobanteHaciendaEnvioService.enviarComprobante(xmlFirmado, comprobante);

        return comprobante;
    }
}
