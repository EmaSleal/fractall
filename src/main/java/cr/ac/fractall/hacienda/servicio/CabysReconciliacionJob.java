package cr.ac.fractall.hacienda.servicio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cr.ac.fractall.hacienda.modelo.Cabys;
import cr.ac.fractall.hacienda.modelo.CabysConsultaLog;
import cr.ac.fractall.hacienda.repositorio.CabysConsultaLogRepository;
import cr.ac.fractall.hacienda.repositorio.CabysRepository;

/**
 * Job diario que resuelve {@code cabys_consulta_log} contra el cache {@code cabys}: cada fila sin
 * procesar que {@link cr.ac.fractall.hacienda.servicio.impl.HaciendaConsultaServiceImpl} fue
 * dejando (ver su javadoc) se inserta en {@code cabys} si el código todavía no existe, o
 * actualiza los campos que hayan cambiado si ya existe -- así el cache se mantiene al día sin que
 * la respuesta HTTP al usuario (crear/editar producto) tenga que esperar por esta escritura.
 *
 * <p>Sin {@code @Transactional} a nivel de método, igual que
 * {@code ComprobanteHaciendaPollingScheduledJob} (ver su javadoc): cada {@code repository.save()}
 * ya es transaccional por sí mismo vía {@code SimpleJpaRepository}, y no hay ninguna transacción
 * larga que sostener acá.
 *
 * <p>Cada registro del log se procesa dentro de su propio try/catch (mismo patrón que
 * {@code ComprobanteHaciendaPollingScheduledJob#procesarEmpresa}): si guardar un {@link Cabys}
 * falla (p. ej. {@code descripcion} nula desde el log, que sí lo permite, contra la columna
 * {@code NOT NULL} de {@code cabys}), ese registro queda {@code procesado = false} para
 * reintentarse mañana, pero NO tumba el resto del batch -- sin este aislamiento, un solo registro
 * corrupto bloquearía la reconciliación de todos los demás códigos CABYS pendientes ese día (y
 * todos los días siguientes, porque el registro roto se seguiría reintentando primero).
 */
@Component
public class CabysReconciliacionJob {

    private static final Logger log = LoggerFactory.getLogger(CabysReconciliacionJob.class);

    private final CabysRepository cabysRepository;
    private final CabysConsultaLogRepository cabysConsultaLogRepository;

    public CabysReconciliacionJob(CabysRepository cabysRepository,
            CabysConsultaLogRepository cabysConsultaLogRepository) {
        this.cabysRepository = cabysRepository;
        this.cabysConsultaLogRepository = cabysConsultaLogRepository;
    }

    @Scheduled(cron = "${application.hacienda.cabys.reconciliacion-cron:0 0 2 * * *}")
    public void reconciliar() {
        List<CabysConsultaLog> pendientes = cabysConsultaLogRepository.findByProcesadoFalse();

        int insertados = 0;
        int actualizados = 0;

        for (CabysConsultaLog logConsulta : pendientes) {
            try {
                Optional<Cabys> existente = cabysRepository.findById(logConsulta.getCodigo());

                if (existente.isEmpty()) {
                    cabysRepository.save(crearDesdeLog(logConsulta));
                    insertados++;
                } else if (aplicarDiferencias(existente.get(), logConsulta)) {
                    cabysRepository.save(existente.get());
                    actualizados++;
                }

                logConsulta.setProcesado(true);
                logConsulta.setProcesadoEn(LocalDateTime.now());
                cabysConsultaLogRepository.save(logConsulta);
            } catch (RuntimeException excepcion) {
                log.error("Error reconciliando el log CABYS {} (código {}): {}",
                        logConsulta.getId(), logConsulta.getCodigo(), excepcion.getMessage(), excepcion);
            }
        }

        log.info("Reconciliación CABYS: {} insertados, {} actualizados, {} sin cambios (de {} pendientes)",
                insertados, actualizados, pendientes.size() - insertados - actualizados, pendientes.size());
    }

    private static Cabys crearDesdeLog(CabysConsultaLog logConsulta) {
        Cabys nuevo = new Cabys();
        nuevo.setCodigo(logConsulta.getCodigo());
        nuevo.setDescripcion(logConsulta.getDescripcion());
        nuevo.setCategorias(logConsulta.getCategorias());
        nuevo.setImpuesto(logConsulta.getImpuesto());
        nuevo.setUri(logConsulta.getUri());
        nuevo.setEstado(logConsulta.getEstado());
        nuevo.setActualizadoEn(LocalDateTime.now());
        return nuevo;
    }

    /**
     * Actualiza {@code cabys} in-place con los datos del log SOLO si algún campo difiere.
     *
     * @return true si se modificó algo (y por lo tanto debe guardarse)
     */
    private static boolean aplicarDiferencias(Cabys cabys, CabysConsultaLog logConsulta) {
        boolean difiere = !Objects.equals(cabys.getDescripcion(), logConsulta.getDescripcion())
                || !Objects.equals(cabys.getImpuesto(), logConsulta.getImpuesto())
                || !Objects.equals(cabys.getCategorias(), logConsulta.getCategorias())
                || !Objects.equals(cabys.getEstado(), logConsulta.getEstado())
                || !Objects.equals(cabys.getUri(), logConsulta.getUri());

        if (!difiere) {
            return false;
        }

        cabys.setDescripcion(logConsulta.getDescripcion());
        cabys.setImpuesto(logConsulta.getImpuesto());
        cabys.setCategorias(logConsulta.getCategorias());
        cabys.setEstado(logConsulta.getEstado());
        cabys.setUri(logConsulta.getUri());
        cabys.setActualizadoEn(LocalDateTime.now());
        return true;
    }
}
