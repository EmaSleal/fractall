package cr.ac.fractall.facturacion.servicio;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaClaveDesconocidaException;
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
 * {@code fechaRespuesta}, {@code ultimoResultadoConsulta} y {@code fechaUltimaConsultaHacienda}, y
 * se incrementa {@code intentosEnvio} -- este último es el mismo contador que
 * {@code ComprobanteHaciendaPollingScheduledJob} usa para su tope de reintentos, se incremente aquí
 * (envío inicial) o allá (sondeo). Si la respuesta trae {@code xmlRespuesta} (el XML de respuesta
 * de Hacienda, en Base64), se decodifica, se cifra y se sube a Object Storage vía
 * {@link ComprobanteXmlCifradoUploader} (misma DEK-por-operación que el XML firmado, ver su
 * javadoc) y se persiste su referencia en {@code xmlRespuestaReferencia}.
 *
 * <p><b>Consulta que nunca llega a Hacienda:</b> si la consulta lanza ANTES de llegar a
 * {@code aplicarRespuesta} (p. ej. {@link CredencialHaciendaNoEncontradaException}),
 * ninguno de los campos de arriba se toca aquí -- es
 * {@code ComprobanteHaciendaPollingScheduledJob#registrarIntentoFallidoYGuardar} quien, en su
 * propio {@code catch}, deja igualmente {@code ultimoResultadoConsulta} en
 * {@value #RESULTADO_ERROR_COMUNICACION} y {@code fechaUltimaConsultaHacienda} en el instante del
 * intento fallido, para que ambos campos nunca queden {@code null} indefinidamente en un
 * comprobante que jamás logra hablar con Hacienda (bug corregido en esta misma sub-tarea; ver su
 * javadoc para el detalle completo).
 *
 * <p><b>Consulta y reenvío ante clave no recibida (ADR-063):</b>
 * {@link #consultarYReenviarSiClaveNoRecibida} es el único punto de la secuencia "consultar,
 * clasificar y reenviar si Hacienda no conoce la clave", compartido por
 * {@code ComprobanteHaciendaPollingScheduledJob} (comprobantes {@code ENVIADO} cuyo POST inicial
 * nunca llegó a Hacienda) y por el reenvío manual de un comprobante en ERROR
 * ({@code ComprobanteEmisionService}, ADR-055). Una clave no recibida ya no es una falla de
 * configuración: se reenvía el XML firmado. Si el POST responde 400 "ya fue recibido anteriormente",
 * el comprobante queda {@value #ESTADO_ENVIADO} con {@code ultimoResultadoConsulta} PENDIENTE
 * ({@link #reenviarComprobante}), nunca RECHAZADO. Un 429 o un 400 sin causa reconocida en la
 * consulta llegan como {@code HaciendaComunicacionException} (ver
 * {@code HaciendaComprobanteApiServiceImpl}); solo las fallas reales de credencial
 * ({@code HaciendaConfiguracionException}) escalan de inmediato (ADR-049).
 *
 * <p>Deliberadamente SIN {@code @Transactional} a nivel de clase/método: ambos métodos públicos
 * hacen una llamada de red real (a Hacienda, y opcionalmente a Vault Transit/Object Storage) antes
 * de la única escritura final, que se apoya -- igual que
 * {@link ComprobanteXmlPersistenceService#generarYPersistirXml}, ver su javadoc para el porqué
 * completo -- en que {@code SimpleJpaRepository#save} ya es transaccional por sí mismo.
 * {@link HaciendaComprobanteApiService#enviarComprobante} no lanza por fallas de Hacienda (devuelve
 * un DTO con {@code codigoMensaje=ERROR}); {@code #consultarComprobante} sí lanza excepciones
 * tipadas ({@code HaciendaConfiguracionException}, {@code HaciendaComunicacionException},
 * {@link HaciendaClaveDesconocidaException}), que {@link #consultarYReenviarSiClaveNoRecibida}
 * clasifica y que el llamador propaga o maneja.
 */
@Service
@Slf4j
public class ComprobanteHaciendaEnvioService {

    static final String ESTADO_ACEPTADO = "ACEPTADO";
    static final String ESTADO_ENVIADO = "ENVIADO";
    static final String ESTADO_RECHAZADO = "RECHAZADO";

    // Valores de ultimo_resultado_consulta (CHECK ampliado en V21) para las ramas de fallo que no
    // llegan a obtener una respuesta clasificable de Hacienda. RESULTADO_ERROR_COMUNICACION es el
    // valor genérico usado por mapearResultadoConsulta y por
    // ComprobanteHaciendaPollingScheduledJob#registrarIntentoFallidoYGuardar (causa COMUNICACIÓN).
    // RESULTADO_ERROR_CONFIGURACION lo escribe solo ComprobanteHaciendaPollingScheduledJob, y solo
    // para fallas reales de credencial (ADR-049 punto 4 / ADR-063 punto 4); una clave no recibida
    // por Hacienda NO es configuración, el job la reenvía (ADR-063).
    static final String RESULTADO_ERROR_COMUNICACION = "ERROR_COMUNICACION";
    static final String RESULTADO_ERROR_CONFIGURACION = "ERROR_CONFIGURACION";

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

    /** Resultado de {@link #consultarParaReenvio}. */
    public enum ResultadoConsultaReenvio {
        /** Hacienda conoce la clave: su respuesta ya se aplicó y se guardó; no hay que hacer POST. */
        APLICADA,
        /** Hacienda nunca recibió la clave (evidencia de P-018): hay que hacer el POST. */
        CLAVE_DESCONOCIDA,
        /** Fallo de comunicación o respuesta no clasificable: no se hace POST ni se toca el estado. */
        AMBIGUA
    }

    /**
     * POST del reenvío manual (ADR-055): mismo flujo que {@link #enviarComprobante}, pero la
     * escritura final también libera el arrendamiento del reclamo (ADR-056). {@code comprobante}
     * debe ser la entidad recargada DESPUÉS del reclamo, para no pisar sus columnas.
     *
     * <p><b>Clave ya recibida:</b> si el POST responde que Hacienda ya tiene esa clave
     * ({@link RespuestaHaciendaDTO#getClaveYaRecibida()}, evidencia de P-018), el comprobante NO
     * se marca RECHAZADO: Hacienda lo tiene y su veredicto real lo obtiene el job de polling
     * (ADR-049), así que queda {@value #ESTADO_ENVIADO} con {@code ultimoResultadoConsulta}
     * PENDIENTE y la causa de Hacienda en {@code mensajeRespuesta}. Pasa con un FIRMADO cuyo POST
     * original llegó a Hacienda sin que se guardara el resultado, o si la clave llega a Hacienda
     * entre la consulta previa y el POST de un ERROR. Sin entrega al cliente: no hay veredicto.
     */
    public void reenviarComprobante(String xmlFirmado, ComprobanteElectronico comprobante) {
        CredencialHacienda credencial = obtenerCredencial(comprobante);
        RespuestaHaciendaDTO respuesta = haciendaComprobanteApiService.enviarComprobante(
                xmlFirmado, comprobante.getClaveNumerica(), credencial.getId());
        if (Boolean.TRUE.equals(respuesta.getClaveYaRecibida())) {
            marcarYaRecibidoPendienteDeVeredicto(comprobante, respuesta);
            comprobante.setReenvioReclamadoHasta(null);
            comprobanteElectronicoRepository.save(comprobante);
            return;
        }
        aplicarRespuesta(comprobante, respuesta);
        comprobante.setReenvioReclamadoHasta(null);
        comprobanteElectronicoRepository.save(comprobante);
        entregarSiAceptado(comprobante);
    }

    private static void marcarYaRecibidoPendienteDeVeredicto(
            ComprobanteElectronico comprobante, RespuestaHaciendaDTO respuesta) {
        String causa = respuesta.getCausaError() != null ? respuesta.getCausaError() : respuesta.getMensaje();
        comprobante.setEstado(ESTADO_ENVIADO);
        comprobante.setCodigoRespuesta(MensajeHacienda.PROCESANDO.getCodigo());
        comprobante.setMensajeRespuesta(truncar(causa, LONGITUD_MAXIMA_MENSAJE_RESPUESTA));
        comprobante.setFechaRespuesta(respuesta.getFechaRespuesta());
        comprobante.setIntentosEnvio(comprobante.getIntentosEnvio() + 1);
        comprobante.setUltimoResultadoConsulta(mapearResultadoConsulta(MensajeHacienda.PROCESANDO));
        comprobante.setFechaUltimaConsultaHacienda(LocalDateTime.now());
    }

    /**
     * Consulta previa del reenvío manual de un comprobante en ERROR (ADR-055 punto 2). Solo
     * aplica la respuesta cuando Hacienda devolvió un veredicto clasificable sobre la clave
     * (aceptado, rechazado o procesando con una respuesta 2xx); en ese caso la escritura final
     * libera el arrendamiento. En los otros dos resultados no escribe nada: el llamador decide.
     *
     * <p>Un 404 o el fallback del circuito abierto también llegan como {@code PROCESANDO}, pero
     * sin un 2xx: no prueban que Hacienda conozca la clave y se clasifican como
     * {@link ResultadoConsultaReenvio#AMBIGUA}. Tampoco se aplica una respuesta {@code ERROR}:
     * {@link #aplicarRespuesta} la convertiría en RECHAZADO.
     *
     * @throws CredencialHaciendaNoEncontradaException si la empresa no tiene credencial
     *     configurada para {@code comprobante.getAmbienteHacienda()}
     */
    public ResultadoConsultaReenvio consultarParaReenvio(ComprobanteElectronico comprobante) {
        Clasificacion clasificacion = consultarYClasificar(comprobante);
        return switch (clasificacion.tipo()) {
            case CLAVE_DESCONOCIDA -> ResultadoConsultaReenvio.CLAVE_DESCONOCIDA;
            case AMBIGUA -> ResultadoConsultaReenvio.AMBIGUA;
            case VEREDICTO -> {
                aplicarVeredictoYGuardar(comprobante, clasificacion.respuesta());
                yield ResultadoConsultaReenvio.APLICADA;
            }
        };
    }

    /**
     * Resultado de {@link #consultarYReenviarSiClaveNoRecibida} (ADR-063 punto 7).
     *
     * @param tipo qué pasó: ver {@link Tipo}
     * @param respuesta la respuesta cruda de la consulta cuando fue {@link Tipo#AMBIGUA} por una
     *     respuesta no clasificable (404, circuito abierto, error de parseo); {@code null} en los
     *     demás casos
     * @param falloConsulta la excepción de la consulta cuando fue {@link Tipo#AMBIGUA} porque la
     *     consulta lanzó; {@code null} en los demás casos
     */
    public record ResultadoConsultaYReenvio(Tipo tipo, RespuestaHaciendaDTO respuesta, RuntimeException falloConsulta) {

        public enum Tipo {
            /** Hacienda conoce la clave y dio un veredicto: ya se aplicó, guardó y (si ACEPTADO) se entregó. */
            VEREDICTO_APLICADO,
            /** Hacienda nunca recibió la clave: ya se hizo el POST y se aplicó su respuesta. */
            REENVIADO,
            /**
             * La consulta falló o no es clasificable: no se hizo POST ni se escribió nada. Qué hacer
             * lo decide cada llamador (el reenvío manual libera su arrendamiento; el job cuenta un
             * intento de comunicación o escala por configuración).
             */
            AMBIGUA
        }
    }

    /**
     * Único punto de la secuencia "consultar, clasificar y reenviar si Hacienda no conoce la clave"
     * (ADR-063 punto 7), usado por {@code ComprobanteHaciendaPollingScheduledJob} y por el reenvío
     * manual de un comprobante en ERROR ({@code ComprobanteEmisionService}, ADR-055). Comparte la
     * clasificación con {@link #consultarParaReenvio}, de modo que hay una sola.
     *
     * <ul>
     *   <li>Veredicto 2xx (aceptado, rechazado, procesando): se aplica, guarda y entrega como en
     *       {@link #consultarYActualizar} -&gt; {@link ResultadoConsultaYReenvio.Tipo#VEREDICTO_APLICADO}.
     *   <li>Clave no recibida ({@link HaciendaClaveDesconocidaException}): se pide el XML firmado a
     *       {@code xmlFirmado} y se hace el POST con {@link #reenviarComprobante} -&gt;
     *       {@link ResultadoConsultaYReenvio.Tipo#REENVIADO}. Un fallo del proveedor del XML (p. ej. la
     *       descarga de Object Storage) se propaga: no es una consulta ambigua. La consulta no
     *       escribe nada, así que el ciclo completo suma exactamente un intento en
     *       {@code intentosEnvio} (el del POST).
     *   <li>Cualquier otra excepción o respuesta no clasificable -&gt;
     *       {@link ResultadoConsultaYReenvio.Tipo#AMBIGUA}, sin POST ni escritura.
     * </ul>
     *
     * <p>Sin {@code @Transactional}: toda la red (Hacienda, Vault, Object Storage) queda fuera de
     * transacción. No agrega lógica de arrendamiento (ADR-056): solo limpia
     * {@code reenvioReclamadoHasta} al escribir, igual que {@link #reenviarComprobante}, lo que es
     * inocuo para el job (un {@code ENVIADO} nunca tiene arrendamiento).
     *
     * @throws CredencialHaciendaNoEncontradaException si la empresa no tiene credencial configurada
     *     para {@code comprobante.getAmbienteHacienda()}
     */
    public ResultadoConsultaYReenvio consultarYReenviarSiClaveNoRecibida(
            ComprobanteElectronico comprobante, Supplier<String> xmlFirmado) {
        Clasificacion clasificacion = consultarYClasificar(comprobante);
        return switch (clasificacion.tipo()) {
            case VEREDICTO -> {
                aplicarVeredictoYGuardar(comprobante, clasificacion.respuesta());
                yield new ResultadoConsultaYReenvio(
                        ResultadoConsultaYReenvio.Tipo.VEREDICTO_APLICADO, null, null);
            }
            case CLAVE_DESCONOCIDA -> {
                reenviarComprobante(xmlFirmado.get(), comprobante);
                yield new ResultadoConsultaYReenvio(ResultadoConsultaYReenvio.Tipo.REENVIADO, null, null);
            }
            case AMBIGUA -> new ResultadoConsultaYReenvio(
                    ResultadoConsultaYReenvio.Tipo.AMBIGUA, clasificacion.respuesta(), clasificacion.fallo());
        };
    }

    /**
     * Aplica, guarda y entrega una respuesta de consulta que no es un veredicto 2xx (404 aún en
     * proceso, circuito abierto, error de parseo): el mismo tratamiento que
     * {@link #consultarYActualizar} le da a cualquier respuesta. Lo usa el job cuando
     * {@link #consultarYReenviarSiClaveNoRecibida} devuelve {@code AMBIGUA} con una respuesta.
     */
    public void aplicarRespuestaDeConsulta(ComprobanteElectronico comprobante, RespuestaHaciendaDTO respuesta) {
        aplicarRespuesta(comprobante, respuesta);
        comprobanteElectronicoRepository.save(comprobante);
        entregarSiAceptado(comprobante);
    }

    private enum TipoClasificacion { VEREDICTO, CLAVE_DESCONOCIDA, AMBIGUA }

    private record Clasificacion(TipoClasificacion tipo, RespuestaHaciendaDTO respuesta, RuntimeException fallo) { }

    /** Consulta y clasifica sin escribir nada; la única clasificación de la consulta previa al reenvío. */
    private Clasificacion consultarYClasificar(ComprobanteElectronico comprobante) {
        CredencialHacienda credencial = obtenerCredencial(comprobante);
        RespuestaHaciendaDTO respuesta;
        try {
            respuesta = haciendaComprobanteApiService.consultarComprobante(
                    comprobante.getClaveNumerica(), credencial.getId());
        } catch (HaciendaClaveDesconocidaException e) {
            return new Clasificacion(TipoClasificacion.CLAVE_DESCONOCIDA, null, null);
        } catch (RuntimeException e) {
            log.warn("Consulta previa al reenvío del comprobante {} sin respuesta clasificable: {}",
                    comprobante.getId(), e.getMessage());
            return new Clasificacion(TipoClasificacion.AMBIGUA, null, e);
        }
        if (!esVeredictoDeHacienda(respuesta)) {
            return new Clasificacion(TipoClasificacion.AMBIGUA, respuesta, null);
        }
        return new Clasificacion(TipoClasificacion.VEREDICTO, respuesta, null);
    }

    private void aplicarVeredictoYGuardar(ComprobanteElectronico comprobante, RespuestaHaciendaDTO respuesta) {
        aplicarRespuesta(comprobante, respuesta);
        comprobante.setReenvioReclamadoHasta(null);
        comprobanteElectronicoRepository.save(comprobante);
        entregarSiAceptado(comprobante);
    }

    private static boolean esVeredictoDeHacienda(RespuestaHaciendaDTO respuesta) {
        Integer codigoHttp = respuesta.getCodigoHttp();
        if (codigoHttp == null || codigoHttp < 200 || codigoHttp >= 300) {
            return false;
        }
        MensajeHacienda codigo = respuesta.getCodigoMensaje();
        return codigo == MensajeHacienda.ACEPTADO
                || codigo == MensajeHacienda.RECHAZADO
                || codigo == MensajeHacienda.RECHAZADO_PARCIAL
                || codigo == MensajeHacienda.PROCESANDO;
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
        comprobante.setUltimoResultadoConsulta(mapearResultadoConsulta(respuesta.getCodigoMensaje()));
        comprobante.setFechaUltimaConsultaHacienda(LocalDateTime.now());

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

    /**
     * Mapea un {@link MensajeHacienda} a los 4 valores permitidos por el CHECK de la columna
     * {@code ultimo_resultado_consulta} (V12): PENDIENTE, ACEPTADO, RECHAZADO, ERROR_COMUNICACION.
     * El mapeo es total e independiente de la lógica de transición de estado de
     * {@code aplicarRespuesta} para no mezclar dos responsabilidades distintas.
     */
    private static String mapearResultadoConsulta(MensajeHacienda codigoMensaje) {
        if (codigoMensaje == null) {
            return RESULTADO_ERROR_COMUNICACION;
        }
        return switch (codigoMensaje) {
            case ACEPTADO -> "ACEPTADO";
            case PROCESANDO -> "PENDIENTE";
            case RECHAZADO, RECHAZADO_PARCIAL -> "RECHAZADO";
            default -> RESULTADO_ERROR_COMUNICACION;
        };
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
