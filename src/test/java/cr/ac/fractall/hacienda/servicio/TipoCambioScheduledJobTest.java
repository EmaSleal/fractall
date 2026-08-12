package cr.ac.fractall.hacienda.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.tenant.TenantContext;


/**
 * Prueba unitaria (Mockito puro, sin contexto de Spring) de {@link TipoCambioScheduledJob} --
 * mismo estilo que {@code CabysReconciliacionJobTest}: sin lógica propia más allá de delegar en
 * el método cache-aware de {@link HaciendaApiService}, mockearlo es suficiente.
 */
@ExtendWith(MockitoExtension.class)
class TipoCambioScheduledJobTest {

    @Mock
    private HaciendaApiService haciendaApiService;

    @InjectMocks
    private TipoCambioScheduledJob job;

    @AfterEach
    void limpiarTenantContext() {
        TenantContext.clear();
    }

    /**
     * Regresión del bug encontrado en producción: sin {@code TenantContextDescartable}, si este
     * job era lo primero en tocar {@code tipoCambioDolarRepository} (p. ej. antes de que
     * cualquier factura en USD hubiera resuelto un tenant real), la creación perezosa de ese bean
     * fallaba con {@code TenantNoResueltoException} -- ver el javadoc de la clase bajo prueba. Un
     * mock puro no puede reproducir la falla de creación de bean en sí (no hay Hibernate real
     * detrás), así que esta prueba verifica el efecto observable del fix: el job fija un tenant
     * descartable durante la llamada y lo limpia al terminar.
     */
    @Test
    void ejecutarFijaUnTenantContextDescartableDuranteLaLlamadaYLoLimpiaAlTerminar() {
        AtomicReference<UUID> tenantDuranteLaLlamada = new AtomicReference<>();
        when(haciendaApiService.consultarTipoCambioDolar()).thenAnswer(invocacion -> {
            tenantDuranteLaLlamada.set(TenantContext.get());
            return null;
        });

        TenantContext.clear();
        job.ejecutar();

        assertThat(tenantDuranteLaLlamada.get()).isNotNull();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void ejecutarLlamaAConsultarTipoCambioDolarExactamenteUnaVez() {
        job.ejecutar();

        verify(haciendaApiService, times(1)).consultarTipoCambioDolar();
    }

    /**
     * Un fallo del método cache-aware (p. ej. Hacienda no disponible y sin valor cacheado para
     * hoy) NO debe propagarse fuera de {@code ejecutar()} -- un fallo de este job de respaldo no
     * debe tumbar el scheduler ni escalar, mismo principio que {@code CabysReconciliacionJob}.
     */
    @Test
    void ejecutarNoPropagaExcepcionCuandoConsultarTipoCambioDolarFalla() {
        when(haciendaApiService.consultarTipoCambioDolar())
                .thenThrow(new TipoCambioNoDisponibleException("Timeout"));

        assertThatCode(() -> job.ejecutar()).doesNotThrowAnyException();

        verify(haciendaApiService, times(1)).consultarTipoCambioDolar();
    }
}
