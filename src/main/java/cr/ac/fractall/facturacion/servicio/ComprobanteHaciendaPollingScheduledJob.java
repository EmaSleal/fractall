package cr.ac.fractall.facturacion.servicio;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaConfiguracionException;
import cr.ac.fractall.notificaciones.servicio.EmailNotificacionService;
import cr.ac.fractall.tenant.TenantContext;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Sondeo periódico de comprobantes en {@value #ESTADO_ENVIADO} (Fase 8): Hacienda puede procesar
 * el envío síncrono de forma asíncrona ({@code debeReintentar=true}, ver
 * {@link ComprobanteHaciendaEnvioService}) -- este job vuelve a preguntar por esos comprobantes
 * hasta que Hacienda entregue una respuesta terminal ({@code ACEPTADO}/{@code RECHAZADO}) o se
 * agoten los reintentos.
 *
 * <p><b>Cómo se descubren las empresas con trabajo pendiente sin conocerlas de antemano:</b>
 * {@link ComprobanteElectronicoRepository#findEmpresaIdsConEstado} es SQL nativo, deliberadamente
 * fuera del filtro automático de {@code @TenantId} (ver su javadoc) -- ningún job anterior de este
 * codebase cruza tenants sobre una entidad tenant-aware, así que esto es un escape hatch nuevo,
 * documentado ahí con detalle. Se invoca bajo un {@link TenantContextDescartable#ejecutar} de
 * descarte SOLO para satisfacer el chequeo fail-closed de Hibernate al abrir el
 * {@code EntityManager} -- el valor en sí es irrelevante porque esa consulta nativa nunca filtra
 * por tenant.
 *
 * <p>Para el trabajo real por empresa, en cambio, se fija {@link TenantContext} al
 * {@code empresaId} REAL descubierto -- nunca un valor de descarte -- porque
 * {@link ComprobanteElectronicoRepository#findByEstado} sí es JPQL y sí depende de ese filtro para
 * devolver solo las filas de esa empresa. {@link TenantContextDescartable} está documentado como
 * seguro solo para entidades SIN {@code @TenantId} ({@code ColaReintentoEmail}); usarlo aquí para
 * el trabajo por-empresa filtraría (o, peor, ocultaría) comprobantes de la empresa equivocada
 * detrás de un UUID que nunca existió.
 *
 * <p>Deliberadamente SIN {@code @Transactional}, ni a nivel de este método ni de uno auxiliar: no
 * hay ninguna transacción larga que sostener aquí (a diferencia de {@code FacturaService#crear},
 * Fase 7) y envolver el trabajo por-empresa en un método {@code @Transactional} propio de esta
 * misma clase reproduciría la auto-invocación que ya mordió a este codebase en la Fase 7 (un
 * método {@code this.metodo(...)} nunca pasa por el proxy de Spring que aplica
 * {@code @Transactional}) -- además de exigir fijar el tenant ANTES de invocar ese método
 * (JpaTransactionManager abre el {@code EntityManager} en {@code doBegin()}, antes del cuerpo del
 * método, ver el javadoc de {@code TenantContextDescartable}), lo que habría forzado la misma
 * auto-invocación que {@code HaciendaComprobanteApiServiceImpl} resuelve con su parámetro
 * {@code self} -- innecesario aquí porque cada llamada a
 * {@link ComprobanteElectronicoRepository}/{@link ComprobanteHaciendaEnvioService} ya es
 * transaccional por sí misma vía {@code SimpleJpaRepository}.
 *
 * <p><b>Backoff exponencial (base {@value #BACKOFF_BASE_MINUTOS} min, tope
 * {@value #BACKOFF_CAP_MINUTOS} min) y máximo {@value #MAX_INTENTOS} intentos:</b> mismo esquema
 * que {@code EmailReintentoScheduledJob} (ver su javadoc), pero {@code comprobante_electronico} no
 * tiene una columna {@code proximo_intento} dedicada y agregar una no se justificó para esta
 * sub-tarea. En su lugar, el próximo intento se deriva en memoria de
 * {@code fechaRespuesta + backoff(intentosEnvio)}, dos campos que
 * {@link ComprobanteHaciendaEnvioService#consultarYActualizar}/{@code #enviarComprobante} ya
 * actualizan en cada intento de todos modos (ver su javadoc). Tras {@value #MAX_INTENTOS} intentos
 * sin respuesta terminal, el comprobante pasa a {@value #ESTADO_ERROR} en lugar de seguir
 * sondeando para siempre.
 */
@Component
public class ComprobanteHaciendaPollingScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ComprobanteHaciendaPollingScheduledJob.class);

    static final long BACKOFF_BASE_MINUTOS = 5;
    static final long BACKOFF_CAP_MINUTOS = 120;
    static final int MAX_INTENTOS = 10;
    // Reutiliza la constante de ComprobanteHaciendaEnvioService (mismo paquete) en vez de
    // redeclararla -- las dos clases deben coincidir siempre en qué string identifica "esperando
    // respuesta de Hacienda", y una segunda copia independiente puede desincronizarse en silencio.
    static final String ESTADO_ENVIADO = ComprobanteHaciendaEnvioService.ESTADO_ENVIADO;
    static final String ESTADO_ERROR = "ERROR";

    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService;
    private final EmpresaRepository empresaRepository;
    private final EmailNotificacionService emailNotificacionService;

    public ComprobanteHaciendaPollingScheduledJob(
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService,
            EmpresaRepository empresaRepository,
            EmailNotificacionService emailNotificacionService) {
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteHaciendaEnvioService = comprobanteHaciendaEnvioService;
        this.empresaRepository = empresaRepository;
        this.emailNotificacionService = emailNotificacionService;
    }

    @Scheduled(fixedDelayString = "${application.hacienda.polling-delay-minutes:5}",
               initialDelayString = "${application.hacienda.polling-delay-minutes:5}",
               timeUnit = TimeUnit.MINUTES)
    public void consultarPendientes() {
        List<UUID> empresaIds = TenantContextDescartable.<List<UUID>>ejecutar(
                () -> comprobanteElectronicoRepository.findEmpresaIdsConEstado(ESTADO_ENVIADO));

        for (UUID empresaId : empresaIds) {
            TenantContext.set(empresaId);
            try {
                procesarEmpresa(empresaId);
            } catch (RuntimeException excepcion) {
                log.error("Error consultando comprobantes pendientes de Hacienda para empresa {}: {}",
                        empresaId, excepcion.getMessage(), excepcion);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void procesarEmpresa(UUID empresaId) {
        List<ComprobanteElectronico> pendientes = comprobanteElectronicoRepository.findByEstado(ESTADO_ENVIADO);
        LocalDateTime ahora = LocalDateTime.now();
        // Corte por credencial (PR6): una vez que un comprobante de esta empresa+ambiente falla por
        // CONFIGURACIÓN en ESTE ciclo, el resto de comprobantes pendientes del MISMO ambiente ya no
        // tienen ninguna razón para volver a golpear a Hacienda -- la credencial rota es la misma
        // para todos ellos (credencialHaciendaRepository.findByEmpresaIdAndAmbiente). Local a este
        // método: exactamente (empresa x ciclo)-scoped, sin ningún riesgo de fuga entre ciclos o
        // entre empresas (a diferencia de un campo de instancia en este @Component, que sería un
        // singleton compartido por todas las empresas y todos los ciclos).
        Set<String> ambientesConFalloConfiguracion = new HashSet<>();
        // Digest de notificación (PR6): comprobantes recién escalados a ESTADO_ERROR EN ESTE ciclo
        // (por cualquiera de las tres rutas de escalación de abajo). Local a procesarEmpresa, igual
        // que el Set de arriba -- findByEstado(ESTADO_ENVIADO) ya garantiza que todo lo que entra a
        // este loop arrancó el ciclo en ENVIADO, así que cualquier cosa agregada aquí transicionó
        // ENVIADO -> ERROR durante ESTE ciclo (no hace falta una columna "recién escalado": el
        // predicate de la consulta ya cumple ese rol).
        List<ComprobanteElectronico> escaladosEnEsteCiclo = new ArrayList<>();

        for (ComprobanteElectronico comprobante : pendientes) {
            if (!listoParaReintentar(comprobante, ahora)) {
                continue;
            }

            if (ambientesConFalloConfiguracion.contains(comprobante.getAmbienteHacienda())) {
                // Mismo ambiente ya marcado como roto en este ciclo: se escala directamente, SIN
                // llamar a consultarYActualizar -- volver a intentar contra la misma credencial
                // rota solo repetiría el mismo fallo de configuración para cada comprobante
                // restante.
                log.warn(
                        "Comprobante {} se escala sin consultar Hacienda: el ambiente {} de la empresa {} ya "
                                + "falló por configuración en este ciclo.",
                        comprobante.getId(), comprobante.getAmbienteHacienda(), empresaId);
                escalarPorCorteDeCredencial(comprobante, escaladosEnEsteCiclo);
                continue;
            }

            try {
                comprobante.setIntentosConsulta(comprobante.getIntentosConsulta() + 1);
                comprobanteHaciendaEnvioService.consultarYActualizar(comprobante);
                escalarSiAgotoIntentos(comprobante, escaladosEnEsteCiclo);
            } catch (HaciendaConfiguracionException excepcion) {
                // Causa CONFIGURACIÓN (credencial/certificado/password ausente, 401 persistente):
                // ningún reintento automático puede resolverla, así que se escala de inmediato en
                // el primer intento en vez de consumir el presupuesto de MAX_INTENTOS reservado
                // para fallas transitorias de comunicación. intentosConsulta ya fue incrementado
                // antes de la llamada en el try. Se marca también el ambiente para cortar el resto
                // de comprobantes pendientes de esta misma empresa+ambiente en este ciclo.
                log.error("Falla de configuración consultando el comprobante {} (empresa {}) en Hacienda: {}",
                        comprobante.getId(), empresaId, excepcion.getMessage(), excepcion);
                ambientesConFalloConfiguracion.add(comprobante.getAmbienteHacienda());
                escalarPorConfiguracion(comprobante, escaladosEnEsteCiclo);
            } catch (RuntimeException excepcion) {
                // Causa COMUNICACIÓN (timeout, 5xx, u otra excepción no clasificada -- fail-safe:
                // una falla desconocida conserva el presupuesto de 10 intentos en vez de escalar
                // instantáneamente). consultarYActualizar puede lanzar ANTES de tocar
                // intentosEnvio/guardar, así que este intento fallido debe contar igual que uno
                // que sí llegó a Hacienda -- si no, el comprobante quedaría reintentando para
                // siempre sin escalar nunca a ESTADO_ERROR. Esta causa NUNCA activa ni consulta el
                // corte por credencial: una falla de comunicación no implica que las demás
                // consultas de este ambiente vayan a fallar igual.
                // intentosConsulta ya fue incrementado antes de la llamada en el try.
                log.error("Error de comunicación consultando el comprobante {} (empresa {}) en Hacienda: {}",
                        comprobante.getId(), empresaId, excepcion.getMessage(), excepcion);
                registrarIntentoFallidoYGuardar(comprobante, escaladosEnEsteCiclo);
            }
        }

        enviarDigestSiHayEscalados(empresaId, escaladosEnEsteCiclo);
    }

    private void escalarSiAgotoIntentos(ComprobanteElectronico comprobante, List<ComprobanteElectronico> escalados) {
        if (ESTADO_ENVIADO.equals(comprobante.getEstado()) && comprobante.getIntentosEnvio() >= MAX_INTENTOS) {
            comprobante.setEstado(ESTADO_ERROR);
            comprobanteElectronicoRepository.save(comprobante);
            escalados.add(comprobante);
            log.warn("Comprobante {} agotó sus {} intentos de confirmación con Hacienda; se marca {}.",
                    comprobante.getId(), MAX_INTENTOS, ESTADO_ERROR);
        }
    }

    /**
     * Causa COMUNICACIÓN: cuenta contra el presupuesto de {@value #MAX_INTENTOS} intentos con el
     * backoff exponencial existente ({@link #listoParaReintentar}/{@link #calcularBackoffMinutos}),
     * igual que antes de distinguir por causa.
     */
    private void registrarIntentoFallidoYGuardar(
            ComprobanteElectronico comprobante, List<ComprobanteElectronico> escalados) {
        LocalDateTime ahora = LocalDateTime.now();
        comprobante.setIntentosEnvio(comprobante.getIntentosEnvio() + 1);
        comprobante.setFechaRespuesta(ahora);
        // ComprobanteHaciendaEnvioService#consultarYActualizar lanzó ANTES de llegar a
        // aplicarRespuesta (ver su javadoc), así que ultimoResultadoConsulta/
        // fechaUltimaConsultaHacienda nunca se tocaron para este intento -- sin esto quedarían
        // null para siempre en un comprobante que jamás logra hablar con Hacienda.
        comprobante.setUltimoResultadoConsulta(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_COMUNICACION);
        comprobante.setFechaUltimaConsultaHacienda(ahora);
        if (comprobante.getIntentosEnvio() >= MAX_INTENTOS) {
            comprobante.setEstado(ESTADO_ERROR);
            escalados.add(comprobante);
            log.warn("Comprobante {} agotó sus {} intentos de confirmación con Hacienda; se marca {}.",
                    comprobante.getId(), MAX_INTENTOS, ESTADO_ERROR);
        }
        comprobanteElectronicoRepository.save(comprobante);
    }

    /**
     * Causa CONFIGURACIÓN: techo de 1 intento -- escala a {@value #ESTADO_ERROR} de inmediato, sin
     * incrementar ni depender de {@code intentosEnvio}/{@value #MAX_INTENTOS}, porque ningún
     * reintento automático puede resolver una credencial/certificado/password ausente o un 401
     * persistente. {@code intentosEnvio} sí se incrementa en 1 (de 0 a 1, dado que ninguna otra
     * rama lo toca para este intento) para que quede un registro consistente de que se intentó una
     * vez, igual que las demás ramas de {@code procesarEmpresa}.
     */
    private void escalarPorConfiguracion(ComprobanteElectronico comprobante, List<ComprobanteElectronico> escalados) {
        LocalDateTime ahora = LocalDateTime.now();
        comprobante.setIntentosEnvio(comprobante.getIntentosEnvio() + 1);
        comprobante.setFechaRespuesta(ahora);
        comprobante.setUltimoResultadoConsulta(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);
        comprobante.setFechaUltimaConsultaHacienda(ahora);
        comprobante.setEstado(ESTADO_ERROR);
        log.warn("Comprobante {} tiene una falla de configuración con Hacienda; se marca {} tras un solo intento.",
                comprobante.getId(), ESTADO_ERROR);
        comprobanteElectronicoRepository.save(comprobante);
        escalados.add(comprobante);
    }

    /**
     * Corte por credencial (PR6): mismo resultado final que {@link #escalarPorConfiguracion} --
     * {@value #ESTADO_ERROR} / {@code ERROR_CONFIGURACION} -- pero SIN incrementar
     * {@code intentosEnvio}, porque a diferencia de esa rama, aquí no se hizo ningún intento real
     * contra Hacienda para este comprobante puntual (se saltó la llamada precisamente por el corte).
     * Incrementar el contador igual falsearía el registro de "cuántas veces se intentó hablar con
     * Hacienda para este comprobante".
     */
    private void escalarPorCorteDeCredencial(
            ComprobanteElectronico comprobante, List<ComprobanteElectronico> escalados) {
        LocalDateTime ahora = LocalDateTime.now();
        comprobante.setFechaRespuesta(ahora);
        comprobante.setUltimoResultadoConsulta(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION);
        comprobante.setFechaUltimaConsultaHacienda(ahora);
        comprobante.setEstado(ESTADO_ERROR);
        comprobanteElectronicoRepository.save(comprobante);
        escalados.add(comprobante);
    }

    /**
     * Digest de notificación (PR6): UN correo por empresa por ciclo, disparado solo si el ciclo
     * escaló al menos un comprobante (por cualquiera de las tres causas anteriores). {@code Empresa}
     * no tiene {@code @TenantId} (a diferencia de {@code ComprobanteElectronico}), así que
     * {@code findById} no está filtrado por tenant y es seguro llamarlo aquí sin depender de
     * {@link TenantContext} -- se busca de forma perezosa, solo cuando la lista no está vacía, para
     * no pagar esta consulta extra en el camino feliz (sin escalaciones) de cada ciclo.
     *
     * <p>Envuelto en {@code try/catch (RuntimeException)}, igual que
     * {@code ComprobanteHaciendaEnvioService#entregarSiAceptado} -- una falla de notificación (o de
     * {@code EmailNotificacionService} en general) nunca debe hacer que este ciclo se reporte como
     * fallido ni afectar el estado ya persistido de los comprobantes escalados.
     */
    private void enviarDigestSiHayEscalados(UUID empresaId, List<ComprobanteElectronico> escalados) {
        if (escalados.isEmpty()) {
            return;
        }

        Empresa empresa = empresaRepository.findById(empresaId).orElse(null);
        String destinatario = empresa == null ? null : empresa.getEmail();
        if (destinatario == null || destinatario.isBlank()) {
            log.warn(
                    "Empresa {} escaló {} comprobante(s) a Hacienda en este ciclo pero no tiene email "
                            + "configurado; no se envía el digest de notificación.",
                    empresaId, escalados.size());
            return;
        }

        try {
            emailNotificacionService.enviarConReintento(
                    destinatario, construirAsuntoDigest(escalados), construirCuerpoDigest(escalados));
        } catch (RuntimeException excepcion) {
            log.error("No se pudo enviar el digest de comprobantes escalados a la empresa {}: {}",
                    empresaId, excepcion.getMessage(), excepcion);
        }
    }

    private static String construirAsuntoDigest(List<ComprobanteElectronico> escalados) {
        return "Comprobantes que requieren intervención manual (" + escalados.size() + ")";
    }

    private static String construirCuerpoDigest(List<ComprobanteElectronico> escalados) {
        StringBuilder cuerpo = new StringBuilder(
                "<p>Los siguientes comprobantes requieren intervención manual:</p><ul>");
        for (ComprobanteElectronico comprobante : escalados) {
            cuerpo.append("<li>Clave numérica: ")
                    .append(comprobante.getClaveNumerica())
                    .append(" -- causa: ")
                    .append(comprobante.getUltimoResultadoConsulta())
                    .append(" -- fecha: ")
                    .append(comprobante.getFechaUltimaConsultaHacienda())
                    .append("</li>");
        }
        cuerpo.append("</ul><p>Los comprobantes con causa ")
                .append(ComprobanteHaciendaEnvioService.RESULTADO_ERROR_CONFIGURACION)
                .append(" requieren corregir la credencial/certificado de Hacienda antes de usar ")
                .append("POST /facturas/{id}/reenviar.</p>");
        return cuerpo.toString();
    }

    private static boolean listoParaReintentar(ComprobanteElectronico comprobante, LocalDateTime ahora) {
        if (comprobante.getFechaRespuesta() == null) {
            return true;
        }
        long backoffMinutos = calcularBackoffMinutos(comprobante.getIntentosEnvio());
        return !ahora.isBefore(comprobante.getFechaRespuesta().plusMinutes(backoffMinutos));
    }

    private static long calcularBackoffMinutos(int intentos) {
        int exponente = Math.max(intentos - 1, 0);
        return Math.min(BACKOFF_BASE_MINUTOS * (1L << exponente), BACKOFF_CAP_MINUTOS);
    }
}
