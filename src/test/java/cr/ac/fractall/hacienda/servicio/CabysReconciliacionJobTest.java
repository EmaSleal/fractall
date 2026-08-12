package cr.ac.fractall.hacienda.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import cr.ac.fractall.hacienda.modelo.Cabys;
import cr.ac.fractall.hacienda.modelo.CabysConsultaLog;
import cr.ac.fractall.hacienda.repositorio.CabysConsultaLogRepository;
import cr.ac.fractall.hacienda.repositorio.CabysRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba unitaria (Mockito puro, sin contexto de Spring ni Testcontainers) de
 * {@link CabysReconciliacionJob} -- a diferencia de {@code ComprobanteHaciendaPollingScheduledJobTest}
 * (que sí necesita Postgres + Vault reales para probar aislamiento multi-tenant cruzando
 * empresas), este job no es tenant-scoped y su única lógica es de mapeo/comparación de campos,
 * así que mockear ambos repositorios es suficiente y evita el costo de un
 * {@code @SpringBootTest} con Testcontainers para esto.
 */
@ExtendWith(MockitoExtension.class)
class CabysReconciliacionJobTest {

    @Mock
    private CabysRepository cabysRepository;

    @Mock
    private CabysConsultaLogRepository cabysConsultaLogRepository;

    @InjectMocks
    private CabysReconciliacionJob job;

    @AfterEach
    void limpiarTenantContext() {
        TenantContext.clear();
    }

    /**
     * Regresión del bug encontrado en producción: sin {@code TenantContextDescartable}, si este
     * job era lo primero en tocar {@code cabysRepository}/{@code cabysConsultaLogRepository}
     * (p. ej. recién desplegado, antes de cualquier request HTTP real), la creación perezosa de
     * esos beans fallaba con {@code TenantNoResueltoException} -- ver el javadoc de la clase bajo
     * prueba. Un mock puro no puede reproducir la falla de creación de bean en sí (no hay
     * Hibernate real detrás), así que esta prueba verifica el efecto observable del fix: el job
     * fija un tenant descartable durante la llamada y lo limpia al terminar.
     */
    @Test
    void reconciliarFijaUnTenantContextDescartableDuranteLaLlamadaYLoLimpiaAlTerminar() {
        AtomicReference<UUID> tenantDuranteLaLlamada = new AtomicReference<>();
        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenAnswer(invocacion -> {
            tenantDuranteLaLlamada.set(TenantContext.get());
            return List.of();
        });

        TenantContext.clear();
        job.reconciliar();

        assertThat(tenantDuranteLaLlamada.get()).isNotNull();
        assertThat(TenantContext.get()).isNull();
    }

    private static CabysConsultaLog nuevoLog(String codigo, String descripcion, Short impuesto,
            String categorias, String estado, String uri) {
        CabysConsultaLog log = new CabysConsultaLog();
        log.setCodigo(codigo);
        log.setDescripcion(descripcion);
        log.setImpuesto(impuesto);
        log.setCategorias(categorias);
        log.setEstado(estado);
        log.setUri(uri);
        log.setConsultadoEn(LocalDateTime.now());
        log.setProcesado(false);
        return log;
    }

    @Test
    void reconciliarInsertaCabysNuevoCuandoNoExisteYMarcaElLogProcesado() {
        CabysConsultaLog log = nuevoLog("2132100000100", "Jugo de tomate concentrado", (short) 13,
                "Alimentos|Bebidas|Jugos", "activo", "https://api.hacienda.go.cr/fe/cabys/2132100000100");

        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenReturn(List.of(log));
        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.empty());

        job.reconciliar();

        ArgumentCaptor<Cabys> captor = ArgumentCaptor.forClass(Cabys.class);
        verify(cabysRepository, times(1)).save(captor.capture());
        Cabys guardado = captor.getValue();
        assertThat(guardado.getCodigo()).isEqualTo("2132100000100");
        assertThat(guardado.getDescripcion()).isEqualTo("Jugo de tomate concentrado");
        assertThat(guardado.getImpuesto()).isEqualTo((short) 13);
        assertThat(guardado.getCategorias()).isEqualTo("Alimentos|Bebidas|Jugos");
        assertThat(guardado.getEstado()).isEqualTo("activo");
        assertThat(guardado.getUri()).isEqualTo("https://api.hacienda.go.cr/fe/cabys/2132100000100");
        assertThat(guardado.getActualizadoEn()).isNotNull();

        verify(cabysConsultaLogRepository, times(1)).save(log);
        assertThat(log.isProcesado()).isTrue();
        assertThat(log.getProcesadoEn()).isNotNull();
    }

    @Test
    void reconciliarActualizaCabysCuandoAlgunCampoDifiereYMarcaElLogProcesado() {
        Cabys existente = new Cabys();
        existente.setCodigo("2132100000100");
        existente.setDescripcion("Descripción vieja");
        existente.setImpuesto((short) 1);
        existente.setCategorias("Alimentos");
        existente.setEstado("activo");
        existente.setUri("https://api.hacienda.go.cr/fe/cabys/2132100000100");
        existente.setActualizadoEn(LocalDateTime.now().minusDays(30));

        CabysConsultaLog log = nuevoLog("2132100000100", "Jugo de tomate concentrado", (short) 13,
                "Alimentos|Bebidas|Jugos", "activo", "https://api.hacienda.go.cr/fe/cabys/2132100000100");

        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenReturn(List.of(log));
        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.of(existente));

        job.reconciliar();

        ArgumentCaptor<Cabys> captor = ArgumentCaptor.forClass(Cabys.class);
        verify(cabysRepository, times(1)).save(captor.capture());
        Cabys guardado = captor.getValue();
        assertThat(guardado.getDescripcion()).isEqualTo("Jugo de tomate concentrado");
        assertThat(guardado.getImpuesto()).isEqualTo((short) 13);
        assertThat(guardado.getCategorias()).isEqualTo("Alimentos|Bebidas|Jugos");

        verify(cabysConsultaLogRepository, times(1)).save(log);
        assertThat(log.isProcesado()).isTrue();
        assertThat(log.getProcesadoEn()).isNotNull();
    }

    @Test
    void reconciliarNoTocaCabysCuandoLosDatosSonIdenticosPeroSiMarcaElLogProcesado() {
        Cabys existente = new Cabys();
        existente.setCodigo("2132100000100");
        existente.setDescripcion("Jugo de tomate concentrado");
        existente.setImpuesto((short) 13);
        existente.setCategorias("Alimentos|Bebidas|Jugos");
        existente.setEstado("activo");
        existente.setUri("https://api.hacienda.go.cr/fe/cabys/2132100000100");
        existente.setActualizadoEn(LocalDateTime.now().minusDays(1));

        CabysConsultaLog log = nuevoLog("2132100000100", "Jugo de tomate concentrado", (short) 13,
                "Alimentos|Bebidas|Jugos", "activo", "https://api.hacienda.go.cr/fe/cabys/2132100000100");

        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenReturn(List.of(log));
        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.of(existente));

        job.reconciliar();

        verify(cabysRepository, never()).save(any());
        verify(cabysConsultaLogRepository, times(1)).save(log);
        assertThat(log.isProcesado()).isTrue();
        assertThat(log.getProcesadoEn()).isNotNull();
    }

    @Test
    void reconciliarSinLogsPendientesNoLlamaAGuardarNada() {
        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenReturn(List.of());

        job.reconciliar();

        verifyNoInteractions(cabysRepository);
        verify(cabysConsultaLogRepository, never()).save(any());
    }

    /**
     * Reproduce el bug encontrado en revisión: un registro cuyo guardado en {@code cabys} falla
     * (p. ej. violación de {@code NOT NULL} si {@code descripcion} llega null desde el log, que sí
     * lo permite) NO debe tumbar el batch completo -- los demás registros pendientes deben seguir
     * procesándose con normalidad, y el registro que falló debe quedar {@code procesado = false}
     * para reintentarse en la corrida siguiente en vez de marcarse falsamente como resuelto.
     */
    @Test
    void reconciliarAislaUnFalloDeGuardadoYSigueConLosDemasRegistros() {
        CabysConsultaLog logConFalla = nuevoLog("1111111111111", null, (short) 13,
                "Alimentos", "activo", null);
        CabysConsultaLog logOk = nuevoLog("2132100000100", "Jugo de tomate concentrado", (short) 13,
                "Alimentos|Bebidas|Jugos", "activo", "https://api.hacienda.go.cr/fe/cabys/2132100000100");

        when(cabysConsultaLogRepository.findByProcesadoFalse()).thenReturn(List.of(logConFalla, logOk));
        when(cabysRepository.findById("1111111111111")).thenReturn(Optional.empty());
        when(cabysRepository.findById("2132100000100")).thenReturn(Optional.empty());
        when(cabysRepository.save(argThat(c -> "1111111111111".equals(c.getCodigo()))))
                .thenThrow(new DataIntegrityViolationException("null value in column \"descripcion\""));

        job.reconciliar();

        // El registro que falló NO se marca procesado -- se reintenta mañana.
        assertThat(logConFalla.isProcesado()).isFalse();
        verify(cabysConsultaLogRepository, never()).save(logConFalla);

        // El segundo registro, sin relación con el que falló, se procesa con normalidad.
        verify(cabysRepository, times(1)).save(argThat(c -> "2132100000100".equals(c.getCodigo())));
        verify(cabysConsultaLogRepository, times(1)).save(logOk);
        assertThat(logOk.isProcesado()).isTrue();
    }
}
