package cr.ac.fractall.facturacion.servicio;

import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;
import lombok.extern.slf4j.Slf4j;

/**
 * Envío inicial de un comprobante ya FIRMADO a Hacienda (síncrono, misma request HTTP que lo firmó
 * -- invocado por {@link ComprobanteXmlPersistenceService#generarYPersistirXml} justo después de
 * persistir {@code FIRMADO}) y actualización de estado a partir de cualquier respuesta de Hacienda,
 * ya sea de ese envío inicial o de una consulta posterior de
 * {@code ComprobanteHaciendaPollingScheduledJob} (sondeo periódico de comprobantes que quedaron
 * {@code ENVIADO}).
 *
 * <p><b>Reglas de transición de estado</b> (compartidas por {@link #enviarComprobante} y
 * {@link #consultarYActualizar} vía {@link #aplicarRespuesta}, para que las dos rutas de entrada
 * nunca diverjan):
 *
 * <ul>
 *   <li>{@code exitoso == true} -&gt; {@value #ESTADO_ACEPTADO} (respuesta terminal positiva).
 *   <li>{@code debeReintentar == true} O {@code codigoMensaje == PROCESANDO} -&gt;
 *       {@value #ESTADO_ENVIADO} (Hacienda todavía está procesando;
 *       {@code ComprobanteHaciendaPollingScheduledJob} lo recoge más adelante). El caso real más
 *       común de esto es un 202 con cuerpo vacío -- el acuse normal de Hacienda de "recibido,
 *       procesando de forma asíncrona" ({@code HaciendaComprobanteApiServiceImpl#parsearRespuesta}
 *       arma ESE caso puntual con {@code exitoso=false} Y {@code debeReintentar=false}
 *       deliberadamente, así que {@code codigoMensaje} es la única señal fiable de que sigue en
 *       trámite; sin este segundo criterio, todo envío exitoso normal caería en el "else" de abajo
 *       y quedaría marcado {@code RECHAZADO} para siempre).
 *   <li>Cualquier otro caso (ni éxito ni reintento) -&gt; {@value #ESTADO_RECHAZADO} (rechazo
 *       síncrono real de Hacienda).
 * </ul>
 *
 * <p>En los tres casos se actualizan {@code codigoRespuesta}, {@code mensajeRespuesta},
 * {@code fechaRespuesta} y se incrementa {@code intentosEnvio} -- este último es el mismo contador
 * que {@code ComprobanteHaciendaPollingScheduledJob} usa para su tope de reintentos, se incremente
 * aquí (envío inicial) o allá (sondeo). Si la respuesta trae {@code xmlRespuesta} (el XML de
 * respuesta de Hacienda, en Base64), se decodifica, se cifra y se sube a Object Storage vía
 * {@link ComprobanteXmlCifradoUploader} (misma DEK-por-operación que el XML firmado, ver su
 * javadoc) y se persiste su referencia en {@code xmlRespuestaReferencia}.
 *
 * <p>Deliberadamente SIN {@code @Transactional} a nivel de clase/método: ambos métodos públicos
 * hacen una llamada de red real (a Hacienda, y opcionalmente a Vault Transit/Object Storage) antes
 * de la única escritura final, que se apoya -- igual que
 * {@link ComprobanteXmlPersistenceService#generarYPersistirXml}, ver su javadoc para el porqué
 * completo -- en que {@code SimpleJpaRepository#save} ya es transaccional por sí mismo. Ninguno de
 * {@link HaciendaComprobanteApiService#enviarComprobante}/{@code #consultarComprobante} lanza
 * excepción (ambos capturan todo internamente y devuelven un DTO con {@code codigoMensaje=ERROR}
 * si algo falla, ver su javadoc), así que no hace falta ningún {@code try/catch} defensivo
 * alrededor de esas llamadas aquí.
 */
@Service
@Slf4j
public class ComprobanteHaciendaEnvioService {

    static final String ESTADO_ACEPTADO = "ACEPTADO";
    static final String ESTADO_ENVIADO = "ENVIADO";
    static final String ESTADO_RECHAZADO = "RECHAZADO";

    private static final int LONGITUD_MAXIMA_MENSAJE_RESPUESTA = 500;

    private final CredencialHaciendaRepository credencialHaciendaRepository;
    private final HaciendaComprobanteApiService haciendaComprobanteApiService;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader;
    private final ComprobanteEntregaService comprobanteEntregaService;

    public ComprobanteHaciendaEnvioService(
            CredencialHaciendaRepository credencialHaciendaRepository,
            HaciendaComprobanteApiService haciendaComprobanteApiService,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader,
            ComprobanteEntregaService comprobanteEntregaService) {
        this.credencialHaciendaRepository = credencialHaciendaRepository;
        this.haciendaComprobanteApiService = haciendaComprobanteApiService;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteXmlCifradoUploader = comprobanteXmlCifradoUploader;
        this.comprobanteEntregaService = comprobanteEntregaService;
    }

    /**
     * Envío síncrono inicial -- {@code xmlFirmado} se recibe en memoria, tal cual lo tiene
     * {@code ComprobanteXmlPersistenceService} justo antes de subirlo/descartarlo (nunca se relee
     * de Object Storage: ese servicio deliberadamente no expone descarga, ver su javadoc).
     *
     * @throws CredencialHaciendaNoEncontradaException si la empresa no tiene credencial
     *     configurada para {@code comprobante.getAmbienteHacienda()}
     */
    public void enviarComprobante(String xmlFirmado, ComprobanteElectronico comprobante) {
        CredencialHacienda credencial = obtenerCredencial(comprobante);
        RespuestaHaciendaDTO respuesta = haciendaComprobanteApiService.enviarComprobante(
                xmlFirmado, comprobante.getClaveNumerica(), credencial.getId());
        aplicarRespuesta(comprobante, respuesta);
        comprobanteElectronicoRepository.save(comprobante);
        entregarSiAceptado(comprobante);
    }

    /**
     * Consulta de seguimiento -- usado por {@code ComprobanteHaciendaPollingScheduledJob} para
     * comprobantes que quedaron {@value #ESTADO_ENVIADO}. Devuelve la respuesta cruda además de
     * mutar/guardar {@code comprobante} para que el job pueda inspeccionar el resultado sin tener
     * que releer la entidad.
     *
     * @throws CredencialHaciendaNoEncontradaException si la empresa no tiene credencial
     *     configurada para {@code comprobante.getAmbienteHacienda()}
     */
    public RespuestaHaciendaDTO consultarYActualizar(ComprobanteElectronico comprobante) {
        CredencialHacienda credencial = obtenerCredencial(comprobante);
        RespuestaHaciendaDTO respuesta = haciendaComprobanteApiService.consultarComprobante(
                comprobante.getClaveNumerica(), credencial.getId());
        aplicarRespuesta(comprobante, respuesta);
        comprobanteElectronicoRepository.save(comprobante);
        entregarSiAceptado(comprobante);
        return respuesta;
    }

    /**
     * Dispara {@link ComprobanteEntregaService#entregar} solo si el comprobante fue ACEPTADO.
     * El fallo de entrega nunca debe cancelar ni revertir el cambio de estado ya persistido
     * (ACEPTADO es un hecho consumado de Hacienda) -- por eso la excepción se trapa aquí y
     * solo se registra en el log.
     */
    private void entregarSiAceptado(ComprobanteElectronico comprobante) {
        if (ESTADO_ACEPTADO.equals(comprobante.getEstado())) {
            try {
                comprobanteEntregaService.entregar(comprobante.getId());
            } catch (RuntimeException e) {
                log.error("Fallo al entregar comprobante {} al cliente: {}", comprobante.getId(), e.getMessage(), e);
            }
        }
    }

    private CredencialHacienda obtenerCredencial(ComprobanteElectronico comprobante) {
        return credencialHaciendaRepository
                .findByEmpresaIdAndAmbiente(comprobante.getEmpresaId(), comprobante.getAmbienteHacienda())
                .orElseThrow(() -> new CredencialHaciendaNoEncontradaException(
                        comprobante.getEmpresaId(), comprobante.getAmbienteHacienda()));
    }

    private void aplicarRespuesta(ComprobanteElectronico comprobante, RespuestaHaciendaDTO respuesta) {
        comprobante.setCodigoRespuesta(
                respuesta.getCodigoMensaje() != null ? respuesta.getCodigoMensaje().getCodigo() : null);
        comprobante.setMensajeRespuesta(truncar(respuesta.getMensaje(), LONGITUD_MAXIMA_MENSAJE_RESPUESTA));
        comprobante.setFechaRespuesta(respuesta.getFechaRespuesta());
        comprobante.setIntentosEnvio(comprobante.getIntentosEnvio() + 1);

        if (Boolean.TRUE.equals(respuesta.getExitoso())) {
            comprobante.setEstado(ESTADO_ACEPTADO);
        } else if (Boolean.TRUE.equals(respuesta.getDebeReintentar())
                || respuesta.getCodigoMensaje() == MensajeHacienda.PROCESANDO) {
            comprobante.setEstado(ESTADO_ENVIADO);
        } else {
            comprobante.setEstado(ESTADO_RECHAZADO);
        }

        if (respuesta.getXmlRespuesta() != null && !respuesta.getXmlRespuesta().isBlank()) {
            byte[] xmlRespuestaClaro = Base64.getDecoder().decode(respuesta.getXmlRespuesta());
            String ruta = construirRutaObjetoRespuesta(comprobante.getEmpresaId(), comprobante.getClaveNumerica());
            String referencia = comprobanteXmlCifradoUploader.cifrarYSubir(xmlRespuestaClaro, ruta);
            comprobante.setXmlRespuestaReferencia(referencia);
            log.info("XML de respuesta de Hacienda para comprobante {} subido a Object Storage: {}",
                    comprobante.getId(), referencia);
        }
    }

    private static String truncar(String valor, int longitudMaxima) {
        if (valor == null || valor.length() <= longitudMaxima) {
            return valor;
        }
        return valor.substring(0, longitudMaxima);
    }

    /**
     * {@code empresas/{empresaId}/comprobantes/{claveNumerica}-respuesta.xml.enc} -- mismo
     * principio de ruta legible y determinística que
     * {@link ComprobanteXmlPersistenceService#construirRutaObjeto}, con el sufijo
     * {@code -respuesta} para que nunca choque con el objeto del XML firmado de ese mismo
     * comprobante.
     */
    private static String construirRutaObjetoRespuesta(UUID empresaId, String claveNumerica) {
        return "empresas/" + empresaId + "/comprobantes/" + claveNumerica + "-respuesta.xml.enc";
    }
}
