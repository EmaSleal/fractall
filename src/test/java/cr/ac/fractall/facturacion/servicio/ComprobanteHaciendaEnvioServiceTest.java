package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.hacienda.dto.MensajeHacienda;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.servicio.HaciendaComprobanteApiService;

/**
 * Prueba unitaria (sin contexto de Spring, mismo enfoque que
 * {@code HaciendaComprobanteApiServiceImplTest}) de {@link ComprobanteHaciendaEnvioService}: todos
 * sus colaboradores (repositorios, cliente de Hacienda, cifrado/subida) van mockeados -- el foco
 * es exclusivamente la lógica de transición de estado descrita en su javadoc.
 *
 * <p>{@code ComprobanteElectronico} no expone {@code setEmpresaId}/{@code setId} (campos
 * gestionados por Hibernate vía {@code @TenantId}/generación en base de datos, ver
 * {@code TenantAwareEntity}/{@code EntidadBase}) -- se fijan vía {@link ReflectionTestUtils}, mismo
 * recurso ya usado en {@code HaciendaConsultaServiceImplTest} para un motivo análogo (campo sin
 * getter/setter público).
 */
class ComprobanteHaciendaEnvioServiceTest {

    private CredencialHaciendaRepository credencialHaciendaRepository;
    private HaciendaComprobanteApiService haciendaComprobanteApiService;
    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private ComprobanteXmlCifradoUploader comprobanteXmlCifradoUploader;
    private ComprobanteEntregaService comprobanteEntregaService;
    private ComprobanteHaciendaEnvioService servicio;

    @BeforeEach
    void configurar() {
        credencialHaciendaRepository = mock(CredencialHaciendaRepository.class);
        haciendaComprobanteApiService = mock(HaciendaComprobanteApiService.class);
        comprobanteElectronicoRepository = mock(ComprobanteElectronicoRepository.class);
        comprobanteXmlCifradoUploader = mock(ComprobanteXmlCifradoUploader.class);
        comprobanteEntregaService = mock(ComprobanteEntregaService.class);
        servicio = new ComprobanteHaciendaEnvioService(
                credencialHaciendaRepository,
                haciendaComprobanteApiService,
                comprobanteElectronicoRepository,
                comprobanteXmlCifradoUploader,
                comprobanteEntregaService);
    }

    private static ComprobanteElectronico nuevoComprobante(UUID empresaId, String ambiente, String claveNumerica) {
        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        ReflectionTestUtils.setField(comprobante, "empresaId", empresaId);
        ReflectionTestUtils.setField(comprobante, "id", UUID.randomUUID());
        comprobante.setAmbienteHacienda(ambiente);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado("FIRMADO");
        comprobante.setIntentosEnvio(0);
        return comprobante;
    }

    private static CredencialHacienda nuevaCredencial(UUID empresaId, String ambiente) {
        CredencialHacienda credencial = new CredencialHacienda();
        ReflectionTestUtils.setField(credencial, "id", UUID.randomUUID());
        credencial.setEmpresaId(empresaId);
        credencial.setAmbiente(ambiente);
        return credencial;
    }

    private static RespuestaHaciendaDTO respuesta(MensajeHacienda mensaje, boolean exitoso, boolean debeReintentar) {
        return RespuestaHaciendaDTO.builder()
                .claveNumerica("clave-x")
                .fechaRespuesta(LocalDateTime.now())
                .codigoMensaje(mensaje)
                .mensaje("mensaje de prueba")
                .exitoso(exitoso)
                .debeReintentar(debeReintentar)
                .codigoHttp(200)
                .build();
    }

    // ========== enviarComprobante ==========

    @Test
    void enviarComprobanteExitosoActualizaEstadoAceptado() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-1");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante("<xml-firmado/>", "clave-1", credencial.getId()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ACEPTADO");
        assertThat(comprobante.getIntentosEnvio()).isEqualTo(1);
        assertThat(comprobante.getCodigoRespuesta()).isEqualTo(MensajeHacienda.ACEPTADO.getCodigo());
        assertThat(comprobante.getFechaRespuesta()).isNotNull();
        verify(comprobanteElectronicoRepository).save(comprobante);
        verifyNoInteractions(comprobanteXmlCifradoUploader);
    }

    @Test
    void enviarComprobanteDebeReintentarActualizaEstadoEnviado() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-2");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ENVIADO");
        verify(comprobanteElectronicoRepository).save(comprobante);
    }

    @Test
    void enviarComprobante202VacioSinDebeReintentarActualizaEstadoEnviado() {
        // Hacienda responde 202 con cuerpo vacío -- su reconocimiento normal de "aceptado para
        // procesamiento asíncrono" (ver HaciendaComprobanteApiServiceImpl#parsearRespuesta). Ese
        // caso arma la respuesta con codigoMensaje=PROCESANDO pero exitoso=false Y
        // debeReintentar=false (confirmado por
        // HaciendaComprobanteApiServiceImplTest#enviarComprobanteConRespuesta202VaciaQuedaEnProcesamiento)
        // -- aplicarRespuesta debe reconocer PROCESANDO como señal de reintento aunque
        // debeReintentar venga en false, no solo caer al "else" y marcar RECHAZADO.
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-2b");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, false));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ENVIADO");
        verify(comprobanteElectronicoRepository).save(comprobante);
    }

    @Test
    void enviarComprobanteRechazadoActualizaEstadoRechazado() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-3");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.RECHAZADO, false, false));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("RECHAZADO");
    }

    @Test
    void enviarComprobanteConXmlRespuestaLaCifraYSubeYFijaReferencia() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-4");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");
        String xmlRespuestaBase64 = Base64.getEncoder()
                .encodeToString("<respuesta/>".getBytes(StandardCharsets.UTF_8));

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));

        RespuestaHaciendaDTO respuestaConXml = RespuestaHaciendaDTO.builder()
                .claveNumerica("clave-4")
                .fechaRespuesta(LocalDateTime.now())
                .codigoMensaje(MensajeHacienda.ACEPTADO)
                .mensaje("Aceptado")
                .xmlRespuesta(xmlRespuestaBase64)
                .exitoso(true)
                .debeReintentar(false)
                .build();
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any())).thenReturn(respuestaConXml);

        String referenciaEsperada = "empresas/" + empresaId + "/comprobantes/clave-4-respuesta.xml.enc";
        when(comprobanteXmlCifradoUploader.cifrarYSubir(any(byte[].class), eq(referenciaEsperada)))
                .thenReturn(referenciaEsperada);

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        ArgumentCaptor<byte[]> contenidoCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(comprobanteXmlCifradoUploader).cifrarYSubir(contenidoCaptor.capture(), eq(referenciaEsperada));
        assertThat(new String(contenidoCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("<respuesta/>");
        assertThat(comprobante.getXmlRespuestaReferencia()).isEqualTo(referenciaEsperada);
    }

    @Test
    void enviarComprobanteSinCredencialLanzaExcepcionYNuncaLlamaAHacienda() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-5");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.enviarComprobante("<xml-firmado/>", comprobante))
                .isInstanceOf(CredencialHaciendaNoEncontradaException.class);

        verifyNoInteractions(haciendaComprobanteApiService);
        verifyNoInteractions(comprobanteElectronicoRepository);
    }

    // ========== consultarYActualizar (usado por el job de sondeo) ==========

    @Test
    void consultarYActualizarAceptadoActualizaEstadoYGuarda() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-6");
        comprobante.setEstado("ENVIADO");
        comprobante.setIntentosEnvio(2);
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.consultarComprobante("clave-6", credencial.getId()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        servicio.consultarYActualizar(comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ACEPTADO");
        assertThat(comprobante.getIntentosEnvio()).isEqualTo(3);
        verify(comprobanteElectronicoRepository).save(comprobante);
    }

    @Test
    void consultarYActualizarSigueEnProcesamientoMantieneEstadoEnviado() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-7");
        comprobante.setEstado("ENVIADO");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        servicio.consultarYActualizar(comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ENVIADO");
    }

    @Test
    void consultarYActualizarSinCredencialLanzaExcepcion() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-8");
        comprobante.setEstado("ENVIADO");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.consultarYActualizar(comprobante))
                .isInstanceOf(CredencialHaciendaNoEncontradaException.class);

        verifyNoInteractions(haciendaComprobanteApiService);
    }

    // ========== T-07: entregarSiAceptado wiring ==========

    @Test
    void enviarComprobanteAceptadoDisparaEntregaDespuesDeGuardar() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07a");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        InOrder orden = inOrder(comprobanteElectronicoRepository, comprobanteEntregaService);
        orden.verify(comprobanteElectronicoRepository).save(comprobante);
        orden.verify(comprobanteEntregaService).entregar(comprobante.getId());
    }

    @Test
    void enviarComprobanteEnviadoNoDisparaEntrega() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07b");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        verify(comprobanteEntregaService, never()).entregar(any());
    }

    @Test
    void enviarComprobanteRechazadoNoDisparaEntrega() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07c");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.RECHAZADO, false, false));

        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        verify(comprobanteEntregaService, never()).entregar(any());
    }

    @Test
    void enviarComprobanteEntregarLanzaExcepcionEsSwallowed() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07d");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.enviarComprobante(any(), any(), any()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));
        doThrow(new RuntimeException("OCI down")).when(comprobanteEntregaService).entregar(any());

        // Should not throw — delivery failure must not break the Hacienda status update
        servicio.enviarComprobante("<xml-firmado/>", comprobante);

        assertThat(comprobante.getEstado()).isEqualTo("ACEPTADO");
        verify(comprobanteElectronicoRepository).save(comprobante);
    }

    @Test
    void consultarYActualizarAceptadoDisparaEntrega() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07e");
        comprobante.setEstado("ENVIADO");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.consultarComprobante("clave-t07e", credencial.getId()))
                .thenReturn(respuesta(MensajeHacienda.ACEPTADO, true, false));

        servicio.consultarYActualizar(comprobante);

        InOrder orden = inOrder(comprobanteElectronicoRepository, comprobanteEntregaService);
        orden.verify(comprobanteElectronicoRepository).save(comprobante);
        orden.verify(comprobanteEntregaService).entregar(comprobante.getId());
    }

    @Test
    void consultarYActualizarNoAceptadoNoDisparaEntrega() {
        UUID empresaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(empresaId, "SANDBOX", "clave-t07f");
        comprobante.setEstado("ENVIADO");
        CredencialHacienda credencial = nuevaCredencial(empresaId, "SANDBOX");

        when(credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresaId, "SANDBOX"))
                .thenReturn(Optional.of(credencial));
        when(haciendaComprobanteApiService.consultarComprobante(any(), any()))
                .thenReturn(respuesta(MensajeHacienda.PROCESANDO, false, true));

        servicio.consultarYActualizar(comprobante);

        verify(comprobanteEntregaService, never()).entregar(any());
    }
}
