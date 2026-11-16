package cr.ac.fractall.facturacion.servicio;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
    private static final String ESTADO_ERROR = "ERROR";
    /** ADR-055 punto 1: RECHAZADO (veredicto funcional) y ENVIADO (lo gestiona el job) quedan fuera. */
    static final Set<String> ESTADOS_REENVIABLES = Set.of("FIRMADO", ESTADO_ERROR);
    static final String MENSAJE_CONSULTA_AMBIGUA =
            "Reenvío manual: no se pudo confirmar el estado en Hacienda; no se reenvió el comprobante.";

    private final ConsecutivoService consecutivoService;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;
    private final ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador;
    private final ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;
    private final long arrendamientoSegundos;

    public ComprobanteEmisionService(
            ConsecutivoService consecutivoService,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteXmlPersistenceService comprobanteXmlPersistenceService,
            ComprobanteXmlCifradoDescargador comprobanteXmlCifradoDescargador,
            ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService,
            @Value("${application.hacienda.reenvio-arrendamiento-segundos:600}") long arrendamientoSegundos) {
        this.consecutivoService = consecutivoService;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteXmlPersistenceService = comprobanteXmlPersistenceService;
        this.comprobanteXmlCifradoDescargador = comprobanteXmlCifradoDescargador;
        this.comprobanteHaciendaEnvioService = comprobanteHaciendaEnvioService;
        this.arrendamientoSegundos = arrendamientoSegundos;
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
     * Regla única de "se puede reenviar manualmente" (ADR-055 punto 1 y ADR-056 punto 6): estado
     * FIRMADO o ERROR, XML firmado persistido y sin arrendamiento de reenvío vigente. La usa
     * {@code FacturaResponse#reenviable} para que los clientes no repliquen la regla.
     */
    public static boolean esReenviable(ComprobanteElectronico comprobante) {
        return ESTADOS_REENVIABLES.contains(comprobante.getEstado())
                && comprobante.getXmlComprobanteReferencia() != null
                && !comprobante.tieneReenvioReclamadoVigente();
    }

    /**
     * Reenvío manual a Hacienda (ADR-055, con el reclamo de ADR-056). Solo para comprobantes en
     * FIRMADO o ERROR con XML firmado persistido; RECHAZADO, ENVIADO y cualquier otro estado
     * responden 409. Devuelve la entidad; la proyección a {@code FacturaResponse} la hace
     * {@code FacturaService}.
     *
     * <ol>
     *   <li>Reclamo atómico en transacción corta ({@code ComprobanteElectronicoRepository#reclamarReenvio}):
     *       fija el arrendamiento con el reloj de la base, incrementa {@code reenviosManuales} y
     *       reinicia {@code intentosEnvio}. Si afecta 0 filas (otro reenvío en curso o el estado
     *       cambió), 409. No cambia {@code estado}.
     *   <li>Se recarga la entidad, para que la escritura final no pise las columnas del reclamo.
     *   <li>FIRMADO: descarga el XML firmado y hace el POST. ERROR: consulta primero el estado en
     *       Hacienda por clave; si Hacienda la conoce aplica esa respuesta sin POST, si no la conoce
     *       hace el POST, y si la consulta falla o es ambigua no hace POST y deja el estado intacto.
     *   <li>La escritura final libera el arrendamiento, sea cual sea el estado resultante. Si algo
     *       lanza antes de esa escritura, se libera con un UPDATE puntual; si la aplicación se cae,
     *       el arrendamiento vence solo.
     * </ol>
     *
     * <p>Sin {@code @Transactional} (ver el javadoc de la clase): la red (Object Storage, Vault
     * Transit, Hacienda) corre fuera de transacción y no se usa {@code SELECT ... FOR UPDATE}.
     *
     * @throws FacturaNoEncontradaException si no existe comprobante para {@code facturaId}
     * @throws ComprobanteNoReenviableException si el estado no es FIRMADO/ERROR, si
     *     {@code xmlComprobanteReferencia} es null o si el reclamo no afectó ninguna fila
     */
    public ComprobanteElectronico reenviar(UUID facturaId) {
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findByFacturaId(facturaId)
                .orElseThrow(() -> new FacturaNoEncontradaException(facturaId));

        if (!ESTADOS_REENVIABLES.contains(comprobante.getEstado())
                || comprobante.getXmlComprobanteReferencia() == null) {
            throw new ComprobanteNoReenviableException(facturaId, comprobante.getEstado());
        }

        UUID comprobanteId = comprobante.getId();
        UUID empresaId = comprobante.getEmpresaId();
        int filas = comprobanteElectronicoRepository.reclamarReenvio(
                comprobanteId, empresaId, arrendamientoSegundos);
        if (filas == 0) {
            throw new ComprobanteNoReenviableException(facturaId, comprobante.getEstado());
        }

        boolean liberado = false;
        try {
            ComprobanteElectronico reclamado = comprobanteElectronicoRepository.findById(comprobanteId)
                    .orElseThrow(() -> new FacturaNoEncontradaException(facturaId));
            if (ESTADO_ERROR.equals(reclamado.getEstado())) {
                reenviarDesdeError(reclamado);
            } else {
                comprobanteHaciendaEnvioService.reenviarComprobante(descargarXmlFirmado(reclamado), reclamado);
            }
            liberado = true;
            return reclamado;
        } finally {
            if (!liberado) {
                comprobanteElectronicoRepository.liberarReenvio(comprobanteId, empresaId);
            }
        }
    }

    private void reenviarDesdeError(ComprobanteElectronico reclamado) {
        switch (comprobanteHaciendaEnvioService.consultarParaReenvio(reclamado)) {
            case APLICADA -> { }
            case CLAVE_DESCONOCIDA ->
                    comprobanteHaciendaEnvioService.reenviarComprobante(descargarXmlFirmado(reclamado), reclamado);
            case AMBIGUA -> {
                reclamado.setMensajeRespuesta(MENSAJE_CONSULTA_AMBIGUA);
                reclamado.setReenvioReclamadoHasta(null);
                comprobanteElectronicoRepository.save(reclamado);
            }
        }
    }

    private String descargarXmlFirmado(ComprobanteElectronico comprobante) {
        byte[] xmlBytes = comprobanteXmlCifradoDescargador.descargarYDescifrar(
                comprobante.getXmlComprobanteReferencia());
        return new String(xmlBytes, StandardCharsets.UTF_8);
    }
}
