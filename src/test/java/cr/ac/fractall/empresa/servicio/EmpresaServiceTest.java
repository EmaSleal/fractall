package cr.ac.fractall.empresa.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;

import cr.ac.fractall.catalogo.repositorio.DistritoRepository;
import cr.ac.fractall.catalogo.servicio.UbicacionInvalidaException;
import cr.ac.fractall.catalogo.servicio.UbicacionValidator;
import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.ConsecutivosAmbienteResponse;
import cr.ac.fractall.empresa.dto.ConsecutivosResponse;
import cr.ac.fractall.empresa.dto.EmpresaResponse;
import cr.ac.fractall.empresa.dto.FijarConsecutivoRequest;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.CertificadoHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaAmbienteHistorialRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.servicio.ConsecutivoInvalidoException;
import cr.ac.fractall.facturacion.servicio.ConsecutivoService;
import cr.ac.fractall.secretos.SecretosKvService;
import cr.ac.fractall.secretos.TransitService;
import cr.ac.fractall.shared.EntidadBase;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Prueba unitaria de {@link EmpresaService#actualizarDatosFiscales} con todos los repositorios
 * mockeados (sin contexto de Spring, sin base de datos real) -- cierra la inconsistencia
 * detectada frente a {@code ClienteService}: antes de V15__catalogo_ubicacion_cr.sql,
 * {@code EmpresaService} no validaba nada del bloque de ubicación.
 *
 * <p>{@code entityManager} se inyecta por reflexión porque {@link EmpresaService} lo recibe vía
 * {@code @PersistenceContext} en un campo, no por constructor -- mismo motivo por el que
 * {@code ClienteServiceListarTest} setea {@code EntidadBase#id} por reflexión (sin setter
 * público por diseño).
 */
class EmpresaServiceTest {

    private EmpresaRepository empresaRepository;
    private DistritoRepository distritoRepository;
    private ConsecutivoService consecutivoService;
    private EmpresaService empresaService;
    private Empresa empresa;
    private UUID empresaId;

    @BeforeEach
    void configurar() throws Exception {
        empresaRepository = mock(EmpresaRepository.class);
        CertificadoHaciendaRepository certificadoHaciendaRepository = mock(CertificadoHaciendaRepository.class);
        CredencialHaciendaRepository credencialHaciendaRepository = mock(CredencialHaciendaRepository.class);
        EmpresaAmbienteHistorialRepository empresaAmbienteHistorialRepository =
                mock(EmpresaAmbienteHistorialRepository.class);
        SecretosKvService secretosKvService = mock(SecretosKvService.class);
        TransitService transitService = mock(TransitService.class);
        distritoRepository = mock(DistritoRepository.class);
        when(distritoRepository.existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo(any(), any(), any()))
                .thenReturn(true);
        consecutivoService = mock(ConsecutivoService.class);

        empresaService = new EmpresaService(
                empresaRepository,
                certificadoHaciendaRepository,
                credencialHaciendaRepository,
                empresaAmbienteHistorialRepository,
                secretosKvService,
                transitService,
                new UbicacionValidator(distritoRepository),
                consecutivoService);

        Field campoEntityManager = EmpresaService.class.getDeclaredField("entityManager");
        campoEntityManager.setAccessible(true);
        campoEntityManager.set(empresaService, mock(EntityManager.class));

        empresaId = UUID.randomUUID();
        TenantContext.set(empresaId);

        empresa = new Empresa();
        setId(empresa, empresaId);
        empresa.setRazonSocial("Empresa de prueba");
        empresa.setAmbienteHacienda("SANDBOX");
        empresa.setStatus("REGISTRADA");

        when(empresaRepository.findById(empresaId)).thenReturn(Optional.of(empresa));
        when(empresaRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(certificadoHaciendaRepository.findByEmpresaIdAndAmbiente(any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    /** Sets the id field on an EntidadBase (no-setter by design) via reflection. */
    private static void setId(Object entidad, UUID id) throws Exception {
        Field campo = EntidadBase.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(entidad, id);
    }

    private static ActualizarDatosFiscalesRequest requestConUbicacion(
            String provincia, String canton, String distrito, String otrasSenas) {
        return new ActualizarDatosFiscalesRequest(
                null, null, null, null, null, provincia, canton, distrito, null, otrasSenas, null, null);
    }

    @Test
    void actualizarDatosFiscalesConUbicacionCompletaYExistenteEnElCatalogoSeGuarda() {
        EmpresaResponse respuesta = empresaService.actualizarDatosFiscales(
                requestConUbicacion("1", "01", "01", "Del parque 200m norte"));

        assertThat(respuesta.codigoProvincia()).isEqualTo("1");
        verify(empresaRepository).saveAndFlush(any());
    }

    @Test
    void actualizarDatosFiscalesConUbicacionParcialSeRechazaSinPersistirNada() {
        ActualizarDatosFiscalesRequest request = requestConUbicacion("1", "01", null, null);

        assertThatThrownBy(() -> empresaService.actualizarDatosFiscales(request))
                .isInstanceOf(UbicacionInvalidaException.class);
        verify(empresaRepository, never()).saveAndFlush(any());
    }

    @Test
    void actualizarDatosFiscalesConUbicacionBienFormadaPeroInexistenteEnElCatalogoSeRechaza() {
        when(distritoRepository.existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo("9", "99", "99"))
                .thenReturn(false);
        ActualizarDatosFiscalesRequest request = requestConUbicacion("9", "99", "99", "Del parque 200m norte");

        assertThatThrownBy(() -> empresaService.actualizarDatosFiscales(request))
                .isInstanceOf(UbicacionInvalidaException.class);
        verify(empresaRepository, never()).saveAndFlush(any());
    }

    @Test
    void actualizarDatosFiscalesSinTocarUbicacionNoValidaNada() {
        EmpresaResponse respuesta = empresaService.actualizarDatosFiscales(
                new ActualizarDatosFiscalesRequest(
                        "Nueva razón social", null, null, null, null, null, null, null, null, null, null, null));

        assertThat(respuesta.razonSocial()).isEqualTo("Nueva razón social");
        verify(empresaRepository).saveAndFlush(any());
    }

    @Test
    void consultarConsecutivosSinFilasDevuelveCerosParaAmbosAmbientes() {
        when(consecutivoService.consultarValores(empresaId)).thenReturn(List.of());

        ConsecutivosResponse respuesta = empresaService.consultarConsecutivos();

        assertThat(respuesta.sandbox()).isEqualTo(new ConsecutivosAmbienteResponse(0, 0, 0, 0));
        assertThat(respuesta.produccion()).isEqualTo(new ConsecutivosAmbienteResponse(0, 0, 0, 0));
    }

    @Test
    void consultarConsecutivosConFilasParcialesCombinaValoresYCerosPorDefecto() {
        when(consecutivoService.consultarValores(empresaId)).thenReturn(List.of(
                new ContadorConsecutivo(empresaId, "SANDBOX", "01", 12L),
                new ContadorConsecutivo(empresaId, "SANDBOX", "03", 3L),
                new ContadorConsecutivo(empresaId, "PRODUCCION", "01", 5L)));

        ConsecutivosResponse respuesta = empresaService.consultarConsecutivos();

        assertThat(respuesta.sandbox()).isEqualTo(new ConsecutivosAmbienteResponse(12, 0, 3, 0));
        assertThat(respuesta.produccion()).isEqualTo(new ConsecutivosAmbienteResponse(5, 0, 0, 0));
    }

    @Test
    void fijarConsecutivoDelegaEnConsecutivoServiceYDevuelveConsecutivosActualizados() {
        FijarConsecutivoRequest request = new FijarConsecutivoRequest("PRODUCCION", "01", 20L);
        when(consecutivoService.fijarValor(empresaId, "PRODUCCION", "01", 20L)).thenReturn(20L);
        when(consecutivoService.consultarValores(empresaId)).thenReturn(List.of(
                new ContadorConsecutivo(empresaId, "PRODUCCION", "01", 20L)));

        ConsecutivosResponse respuesta = empresaService.fijarConsecutivo(request);

        assertThat(respuesta.produccion().facturaElectronica()).isEqualTo(20L);
        verify(consecutivoService).fijarValor(empresaId, "PRODUCCION", "01", 20L);
    }

    @Test
    void fijarConsecutivoConTipoComprobanteInvalidoLanzaIllegalArgumentExceptionSinLlamarAlServicio() {
        FijarConsecutivoRequest request = new FijarConsecutivoRequest("PRODUCCION", "99", 20L);

        assertThatThrownBy(() -> empresaService.fijarConsecutivo(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(consecutivoService, never()).fijarValor(any(), any(), any(), anyLong());
    }

    @Test
    void fijarConsecutivoPropagaConsecutivoInvalidoExceptionDelServicio() {
        FijarConsecutivoRequest request = new FijarConsecutivoRequest("SANDBOX", "01", 1L);
        when(consecutivoService.fijarValor(empresaId, "SANDBOX", "01", 1L))
                .thenThrow(new ConsecutivoInvalidoException(5L, 1L));

        assertThatThrownBy(() -> empresaService.fijarConsecutivo(request))
                .isInstanceOf(ConsecutivoInvalidoException.class);
    }
}
