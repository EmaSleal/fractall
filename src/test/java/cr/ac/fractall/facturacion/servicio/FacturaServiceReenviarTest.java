package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;

/**
 * Prueba unitaria (sin contexto de Spring) de {@link FacturaService#reenviar} -- Fase B (ver
 * diseño D-B): la lógica de estado/descarga/reenvío se movió a
 * {@link ComprobanteEmisionService#reenviar} (cubierta en detalle por
 * {@code ComprobanteEmisionServiceTest}). Lo que queda bajo responsabilidad de
 * {@code FacturaService} -- y lo que esta prueba cubre -- es la delegación y la proyección final a
 * {@link FacturaResponse} vía {@link FacturaService#obtener}.
 */
class FacturaServiceReenviarTest {

    private ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private FacturaRepository facturaRepository;
    private LineaFacturaRepository lineaFacturaRepository;
    private FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private FacturaMedioPagoRepository facturaMedioPagoRepository;
    private ComprobanteEmisionService comprobanteEmisionService;

    private FacturaService facturaService;

    @BeforeEach
    void configurar() {
        comprobanteElectronicoRepository = mock(ComprobanteElectronicoRepository.class);
        facturaRepository = mock(FacturaRepository.class);
        lineaFacturaRepository = mock(LineaFacturaRepository.class);
        facturaOtrosCargosRepository = mock(FacturaOtrosCargosRepository.class);
        facturaInformacionReferenciaRepository = mock(FacturaInformacionReferenciaRepository.class);
        facturaMedioPagoRepository = mock(FacturaMedioPagoRepository.class);
        comprobanteEmisionService = mock(ComprobanteEmisionService.class);

        facturaService = new FacturaService(
                mock(ClienteRepository.class),
                mock(EmpresaRepository.class),
                facturaRepository,
                lineaFacturaRepository,
                comprobanteElectronicoRepository,
                mock(LineaCodigoComercialRepository.class),
                mock(LineaDescuentoRepository.class),
                mock(ImpuestoLineaExoneracionRepository.class),
                facturaOtrosCargosRepository,
                facturaInformacionReferenciaRepository,
                facturaMedioPagoRepository,
                mock(LineaFacturaEnsamblador.class),
                comprobanteEmisionService,
                mock(HaciendaApiService.class));
    }

    private static ComprobanteElectronico nuevoComprobante(UUID facturaId) {
        ComprobanteElectronico c = new ComprobanteElectronico();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setFacturaId(facturaId);
        c.setEstado("ENVIADO");
        c.setIntentosEnvio(0);
        c.setIntentosConsulta(0);
        c.setAmbienteHacienda("SANDBOX");
        c.setTipoComprobante("01");
        c.setConsecutivo("00000000000000000001");
        c.setClaveNumerica("5" + "0".repeat(49));
        c.setFechaEmision(LocalDateTime.now());
        return c;
    }

    private static Factura nuevaFactura(UUID facturaId) {
        Factura f = new Factura();
        ReflectionTestUtils.setField(f, "id", facturaId);
        f.setClienteId(UUID.randomUUID());
        f.setCondicionVenta("01");
        f.setMedioPago("01");
        f.setMoneda("CRC");
        f.setTipoCambio(BigDecimal.ONE);
        f.setSubtotal(new BigDecimal("1000.00000"));
        f.setTotalImpuesto(new BigDecimal("130.00000"));
        f.setTotal(new BigDecimal("1130.00000"));
        f.setTotalIvaDevuelto(BigDecimal.ZERO);
        f.setCreateDate(LocalDateTime.now());
        f.setUpdateDate(LocalDateTime.now());
        return f;
    }

    private void stubObtener(UUID facturaId, ComprobanteElectronico comprobante) {
        Factura factura = nuevaFactura(facturaId);
        when(facturaRepository.findById(facturaId)).thenReturn(Optional.of(factura));
        when(comprobanteElectronicoRepository.findByFacturaId(facturaId)).thenReturn(Optional.of(comprobante));
        when(lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(facturaId)).thenReturn(List.of());
        when(facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
        when(facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
        when(facturaMedioPagoRepository.findByFacturaIdOrderByOrden(facturaId)).thenReturn(List.of());
    }

    @Test
    void reenviarDelegaEnComprobanteEmisionServiceYLuegoRetornaLaProyeccionDeObtener() {
        UUID facturaId = UUID.randomUUID();
        ComprobanteElectronico comprobante = nuevoComprobante(facturaId);
        stubObtener(facturaId, comprobante);

        FacturaResponse respuesta = facturaService.reenviar(facturaId);

        verify(comprobanteEmisionService).reenviar(facturaId);
        verify(facturaRepository).findById(facturaId);
        assertThat(respuesta).isNotNull();
        assertThat(respuesta.id()).isEqualTo(facturaId);
    }

    @Test
    void reenviarPropagaComprobanteNoReenviableSinLlamarObtener() {
        UUID facturaId = UUID.randomUUID();
        when(comprobanteEmisionService.reenviar(facturaId))
                .thenThrow(new ComprobanteNoReenviableException(facturaId, "GENERADO"));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(ComprobanteNoReenviableException.class);

        verify(facturaRepository, never()).findById(eq(facturaId));
    }

    @Test
    void reenviarPropagaFacturaNoEncontradaSinLlamarObtener() {
        UUID facturaId = UUID.randomUUID();
        when(comprobanteEmisionService.reenviar(facturaId))
                .thenThrow(new FacturaNoEncontradaException(facturaId));

        assertThatThrownBy(() -> facturaService.reenviar(facturaId))
                .isInstanceOf(FacturaNoEncontradaException.class);

        verify(facturaRepository, never()).findById(eq(facturaId));
    }
}
