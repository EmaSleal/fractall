package cr.ac.fractall.catalogo.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.dto.ActualizarProductoRequest;
import cr.ac.fractall.catalogo.dto.CrearProductoRequest;
import cr.ac.fractall.catalogo.dto.ProductoResponse;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.dto.CabysDTO;

/**
 * Prueba unitaria de {@link ProductoService} con {@code HaciendaApiService} y
 * {@code ProductoRepository} mockeados (sin contexto de Spring, sin base de datos real) -- el
 * enfoque de mock cubre las 4 ramas de validación CABYS de la sección 4.10 de
 * {@code arquitectura-facturacion-electronica-cr.md} sin necesitar Testcontainers para cada
 * caso; la cobertura de extremo a extremo contra la base real vive en
 * {@code CatalogoControllerTest}.
 */
class ProductoServiceTest {

    private ProductoRepository productoRepository;
    private HaciendaApiService haciendaApiService;
    private ProductoService productoService;

    @BeforeEach
    void configurar() {
        productoRepository = mock(ProductoRepository.class);
        haciendaApiService = mock(HaciendaApiService.class);
        productoService = new ProductoService(productoRepository, haciendaApiService);
        when(productoRepository.findByCodigo(any())).thenReturn(Optional.empty());
        when(productoRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CabysBusquedaDTO respuestaConUnCodigo(String codigo, String descripcion, Integer impuesto) {
        return CabysBusquedaDTO.builder()
                .exitosa(true)
                .total(1)
                .cantidad(1)
                .cabys(List.of(CabysDTO.builder()
                        .codigo(codigo)
                        .descripcion(descripcion)
                        .impuesto(impuesto)
                        .build()))
                .build();
    }

    @Test
    void crearConCodigoCabysValidoDerivaLosCuatroCamposDesdeHacienda() {
        when(haciendaApiService.buscarCabys("2132100000100", 25))
                .thenReturn(respuestaConUnCodigo("2132100000100", "Jugo de tomate concentrado", 13));

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-001", "Jugo de tomate", "2132100000100", null, new BigDecimal("1500.00000"), null);

        ProductoResponse respuesta = productoService.crear(request);

        assertThat(respuesta.descripcionCabys()).isEqualTo("Jugo de tomate concentrado");
        assertThat(respuesta.porcentajeImpuesto()).isEqualByComparingTo("13");
        assertThat(respuesta.gravado()).isTrue();
        assertThat(respuesta.cabysValidadoEn()).isNotNull();
        assertThat(respuesta.codigoUnidadFe()).isEqualTo("Unid");
        verify(productoRepository).saveAndFlush(any());
    }

    @Test
    void crearCuandoLaLlamadaAHaciendaFallaLanzaHaciendaNoDisponibleYNoPersisteNada() {
        when(haciendaApiService.buscarCabys("2132100000100", 25))
                .thenReturn(CabysBusquedaDTO.builder()
                        .exitosa(false)
                        .mensajeError("Timeout al conectar con Hacienda")
                        .build());

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-005", "Jugo de tomate", "2132100000100", null, BigDecimal.TEN, null);

        assertThatThrownBy(() -> productoService.crear(request))
                .isInstanceOf(HaciendaNoDisponibleException.class);
        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearCuandoLaLlamadaAHaciendaTraeExitosaNulaLanzaHaciendaNoDisponible() {
        when(haciendaApiService.buscarCabys("2132100000100", 25))
                .thenReturn(CabysBusquedaDTO.builder().build());

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-006", "Jugo de tomate", "2132100000100", null, BigDecimal.TEN, null);

        assertThatThrownBy(() -> productoService.crear(request))
                .isInstanceOf(HaciendaNoDisponibleException.class);
        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConCodigoCabysSinCoincidenciaExactaSeRechazaYNoPersisteNada() {
        when(haciendaApiService.buscarCabys("9999999999999", 25))
                .thenReturn(respuestaConUnCodigo("2132100000100", "Otro producto", 13));

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-002", "Producto inexistente", "9999999999999", null, BigDecimal.TEN, null);

        assertThatThrownBy(() -> productoService.crear(request))
                .isInstanceOf(CodigoCabysInvalidoException.class);
        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConCabysSinCampoImpuestoSeRechazaYNoPersisteNada() {
        when(haciendaApiService.buscarCabys("2132100000100", 25))
                .thenReturn(respuestaConUnCodigo("2132100000100", "Jugo de tomate concentrado", null));

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-003", "Jugo de tomate", "2132100000100", null, BigDecimal.TEN, null);

        assertThatThrownBy(() -> productoService.crear(request))
                .isInstanceOf(CabysSinImpuestoException.class);
        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConCodigoDuplicadoSeRechazaSinLlamarAHacienda() {
        when(productoRepository.findByCodigo("PROD-001")).thenReturn(Optional.of(new Producto()));

        CrearProductoRequest request = new CrearProductoRequest(
                "PROD-001", "Duplicado", "2132100000100", null, BigDecimal.TEN, null);

        assertThatThrownBy(() -> productoService.crear(request))
                .isInstanceOf(ProductoDuplicadoException.class);
        verify(haciendaApiService, never()).buscarCabys(any(), any());
        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    void actualizarSinCambiarCodigoCabysNoLlamaAHacienda() {
        Producto existente = new Producto();
        existente.setCodigo("PROD-004");
        existente.setCodigoCabys("2132100000100");
        existente.setPrecioVenta(BigDecimal.TEN);
        when(productoRepository.findById(any())).thenReturn(Optional.of(existente));

        ActualizarProductoRequest request = new ActualizarProductoRequest(
                null, "Nueva descripción", null, null, null, null);

        productoService.actualizar(java.util.UUID.randomUUID(), request);

        verify(haciendaApiService, never()).buscarCabys(any(), any());
        assertThat(existente.getDescripcion()).isEqualTo("Nueva descripción");
    }
}
