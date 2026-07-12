package cr.ac.fractall.facturacion.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
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

    public ComprobanteHaciendaPollingScheduledJob(
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            ComprobanteHaciendaEnvioService comprobanteHaciendaEnvioService) {
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.comprobanteHaciendaEnvioService = comprobanteHaciendaEnvioService;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
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

        for (ComprobanteElectronico comprobante : pendientes) {
            if (!listoParaReintentar(comprobante, ahora)) {
                continue;
            }

            try {
                comprobanteHaciendaEnvioService.consultarYActualizar(comprobante);
                escalarSiAgotoIntentos(comprobante);
            } catch (RuntimeException excepcion) {
                // consultarYActualizar puede lanzar ANTES de tocar intentosEnvio/guardar (p. ej.
                // CredencialHaciendaNoEncontradaException, si la credencial se borró mientras el
                // comprobante esperaba). Si este intento fallido no se contara igual que uno que sí
                // llegó a Hacienda, el comprobante quedaría reintentando para siempre sin escalar
                // nunca a ESTADO_ERROR -- por eso esta rama también incrementa/guarda, a diferencia
                // de simplemente loguear y seguir.
                log.error("Error consultando el comprobante {} (empresa {}) en Hacienda: {}",
                        comprobante.getId(), empresaId, excepcion.getMessage(), excepcion);
                registrarIntentoFallidoYGuardar(comprobante);
            }
        }
    }

    private void escalarSiAgotoIntentos(ComprobanteElectronico comprobante) {
        if (ESTADO_ENVIADO.equals(comprobante.getEstado()) && comprobante.getIntentosEnvio() >= MAX_INTENTOS) {
            comprobante.setEstado(ESTADO_ERROR);
            comprobanteElectronicoRepository.save(comprobante);
            log.warn("Comprobante {} agotó sus {} intentos de confirmación con Hacienda; se marca {}.",
                    comprobante.getId(), MAX_INTENTOS, ESTADO_ERROR);
        }
    }

    private void registrarIntentoFallidoYGuardar(ComprobanteElectronico comprobante) {
        comprobante.setIntentosEnvio(comprobante.getIntentosEnvio() + 1);
        comprobante.setFechaRespuesta(LocalDateTime.now());
        if (comprobante.getIntentosEnvio() >= MAX_INTENTOS) {
            comprobante.setEstado(ESTADO_ERROR);
            log.warn("Comprobante {} agotó sus {} intentos de confirmación con Hacienda; se marca {}.",
                    comprobante.getId(), MAX_INTENTOS, ESTADO_ERROR);
        }
        comprobanteElectronicoRepository.save(comprobante);
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
