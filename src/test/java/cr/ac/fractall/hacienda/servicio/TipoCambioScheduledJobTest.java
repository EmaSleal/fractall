package cr.ac.fractall.hacienda.servicio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


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
