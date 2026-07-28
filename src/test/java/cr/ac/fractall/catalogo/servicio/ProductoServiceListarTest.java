package cr.ac.fractall.catalogo.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cr.ac.fractall.catalogo.dto.ProductoResponse;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.hacienda.servicio.HaciendaApiService;
import cr.ac.fractall.shared.PaginaResponse;

/**
 * Prueba unitaria de {@link ProductoService#listar} y {@link ProductoService#obtener} con
 * {@code ProductoRepository} mockeado (sin contexto de Spring, sin base de datos real).
 * Cubre los escenarios de paginación keyset: última página (nextCursor null) y más páginas
 * (nextCursor = id del elemento limit+1). Spec: FR-1 "More pages exist" + "Last page", FR-2
 * "Non-existent".
 */
class ProductoServiceListarTest {

    private ProductoRepository productoRepository;
    private HaciendaApiService haciendaApiService;
    private ProductoService productoService;

    @BeforeEach
    void configurar() {
        productoRepository = mock(ProductoRepository.class);
        haciendaApiService = mock(HaciendaApiService.class);
        productoService = new ProductoService(productoRepository, haciendaApiService);
    }

    /** Sets the id field on an EntidadBase (no-setter by design) via reflection. */
    private static void setId(Object entity, UUID id) throws Exception {
        Field campo = cr.ac.fractall.shared.EntidadBase.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(entity, id);
    }

    /** Builds a minimal Producto with a deterministic UUID based on index. */
    private static Producto productoConId(int index) throws Exception {
        Producto p = new Producto();
        setId(p, UUID.fromString(String.format("00000000-0000-0000-0000-%012d", index)));
        p.setCodigo("PROD-" + index);
        p.setDescripcion("Producto " + index);
        p.setCodigoCabys("2132100000100");
        p.setDescripcionCabys("Desc " + index);
        p.setPrecioVenta(BigDecimal.TEN);
        p.setActivo(true);
        p.setGravado(true);
        p.setPorcentajeImpuesto(new BigDecimal("13"));
        return p;
    }

    private static List<Producto> generarProductos(int cantidad) throws Exception {
        List<Producto> lista = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            lista.add(productoConId(i));
        }
        return lista;
    }

    @Test
    void listarCuandoHayMenosItemsQueLimitRetornaNextCursorNull() throws Exception {
        // 5 items, limit=20 — repository returns 5 (no extras), nextCursor must be null.
        List<Producto> cincoItems = generarProductos(5);
        when(productoRepository.buscar(eq(true), isNull(), isNull(), eq(21))).thenReturn(cincoItems);

        PaginaResponse<ProductoResponse> respuesta = productoService.listar(true, null, null, 20);

        assertThat(respuesta.items()).hasSize(5);
        assertThat(respuesta.nextCursor()).isNull();
    }

    @Test
    void listarCuandoHayMasItemsQueLimitRetornaNextCursorIgualAlIdDelElementoLimit() throws Exception {
        // 21 items returned from repo (limit+1=21), service must slice to 20 items
        // and set nextCursor = id of item 20 (last item after slicing).
        List<Producto> veintiUnItems = generarProductos(21);
        UUID idDelItem20 = veintiUnItems.get(19).getId();
        when(productoRepository.buscar(eq(true), isNull(), isNull(), eq(21))).thenReturn(veintiUnItems);

        PaginaResponse<ProductoResponse> respuesta = productoService.listar(true, null, null, 20);

        assertThat(respuesta.items()).hasSize(20);
        assertThat(respuesta.nextCursor()).isEqualTo(idDelItem20);
    }

    @Test
    void obtenerConIdInexistenteLanzaProductoNoEncontradoException() {
        UUID idDesconocido = UUID.randomUUID();
        when(productoRepository.findById(idDesconocido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productoService.obtener(idDesconocido))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }
}
