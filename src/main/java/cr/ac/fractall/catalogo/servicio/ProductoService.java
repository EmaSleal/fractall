package cr.ac.fractall.catalogo.servicio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.catalogo.Producto;
import cr.ac.fractall.catalogo.ProductoRepository;
import cr.ac.fractall.catalogo.dto.ActualizarProductoRequest;
import cr.ac.fractall.catalogo.dto.CrearProductoRequest;
import cr.ac.fractall.catalogo.dto.ProductoResponse;
import cr.ac.fractall.hacienda.HaciendaApiService;
import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.dto.CabysDTO;

import java.util.UUID;

/**
 * Alta/edición de {@code producto} con validación de CABYS contra la API de Hacienda (Fase 6,
 * sección 4.10 de {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>{@code empresa_id} nunca se asigna manualmente: {@code Producto} extiende
 * {@link cr.ac.fractall.tenant.TenantAwareEntity}, así que Hibernate lo escribe automáticamente
 * desde {@link cr.ac.fractall.tenant.TenantContext} al momento del flush (ver el javadoc de
 * {@code TenantAwareEntity}).
 */
@Service
public class ProductoService {

    /**
     * Se pide un lote más amplio que el default de la API (10) para maximizar la probabilidad
     * de que el código exacto buscado esté entre los resultados devueltos -- la búsqueda de
     * Hacienda es por texto/similaridad, no por código exacto, así que un código válido puede
     * no aparecer en las primeras posiciones.
     */
    private static final int TOP_BUSQUEDA_VALIDACION = 25;

    private final ProductoRepository productoRepository;
    private final HaciendaApiService haciendaApiService;

    public ProductoService(ProductoRepository productoRepository, HaciendaApiService haciendaApiService) {
        this.productoRepository = productoRepository;
        this.haciendaApiService = haciendaApiService;
    }

    public CabysBusquedaDTO buscarCabys(String busqueda, Integer top) {
        return haciendaApiService.buscarCabys(busqueda, top);
    }

    @Transactional
    public ProductoResponse crear(CrearProductoRequest request) {
        if (productoRepository.findByCodigo(request.codigo()).isPresent()) {
            throw new ProductoDuplicadoException(request.codigo());
        }

        CabysDTO cabysValidado = validarYObtenerCabys(request.codigoCabys());

        Producto producto = new Producto();
        producto.setCodigo(request.codigo());
        producto.setDescripcion(request.descripcion());
        aplicarValidacionCabys(producto, cabysValidado);
        producto.setCodigoUnidadFe(request.codigoUnidadFe() != null ? request.codigoUnidadFe() : "Unid");
        producto.setPrecioVenta(request.precioVenta());
        producto.setActivo(request.activo() == null || request.activo());

        LocalDateTime ahora = LocalDateTime.now();
        producto.setCreateDate(ahora);
        producto.setUpdateDate(ahora);

        productoRepository.saveAndFlush(producto);
        return ProductoResponse.desde(producto);
    }

    @Transactional
    public ProductoResponse actualizar(UUID id, ActualizarProductoRequest request) {
        Producto producto = productoRepository.findById(id)
                .orElseThrow(() -> new ProductoNoEncontradoException(id));

        if (request.codigo() != null && !request.codigo().equals(producto.getCodigo())) {
            productoRepository.findByCodigo(request.codigo()).ifPresent(existente -> {
                throw new ProductoDuplicadoException(request.codigo());
            });
        }

        // El CABYS solo se re-valida contra Hacienda si el cliente efectivamente lo cambia --
        // re-validar en cada PATCH sin cambio de código sería una llamada de red innecesaria.
        if (request.codigoCabys() != null && !request.codigoCabys().equals(producto.getCodigoCabys())) {
            CabysDTO cabysValidado = validarYObtenerCabys(request.codigoCabys());
            aplicarValidacionCabys(producto, cabysValidado);
        }

        aplicarSiNoEsNulo(request.codigo(), producto::setCodigo);
        aplicarSiNoEsNulo(request.descripcion(), producto::setDescripcion);
        aplicarSiNoEsNulo(request.codigoUnidadFe(), producto::setCodigoUnidadFe);
        aplicarSiNoEsNulo(request.precioVenta(), producto::setPrecioVenta);
        aplicarSiNoEsNulo(request.activo(), producto::setActivo);
        producto.setUpdateDate(LocalDateTime.now());

        productoRepository.saveAndFlush(producto);
        return ProductoResponse.desde(producto);
    }

    /**
     * Busca el {@code codigoCabys} recibido y exige coincidencia EXACTA sobre el campo propio
     * {@code codigo} de alguno de los resultados -- ninguna coincidencia parcial ni fallback
     * (sección 4.10). Rechaza también, con una excepción distinta, si el campo {@code impuesto}
     * de la coincidencia llega vacío o nulo (requisito duro de la sección 10 del documento de
     * arquitectura).
     *
     * <p>Antes de interpretar la ausencia de coincidencia, se verifica
     * {@link CabysBusquedaDTO#getExitosa()}: si la llamada a Hacienda en sí falló (red, 5xx,
     * timeout), NO se concluye "código inválido" -- se lanza {@link HaciendaNoDisponibleException}
     * en su lugar, para no conflacionar una caída de la dependencia externa con un error genuino
     * del cliente.
     */
    private CabysDTO validarYObtenerCabys(String codigoCabys) {
        CabysBusquedaDTO resultado = haciendaApiService.buscarCabys(codigoCabys, TOP_BUSQUEDA_VALIDACION);

        if (!Boolean.TRUE.equals(resultado.getExitosa())) {
            throw new HaciendaNoDisponibleException(resultado.getMensajeError());
        }

        List<CabysDTO> candidatos = resultado.getCabys();

        CabysDTO coincidenciaExacta = candidatos == null ? null : candidatos.stream()
                .filter(c -> codigoCabys.equals(c.getCodigo()))
                .findFirst()
                .orElse(null);

        if (coincidenciaExacta == null) {
            throw new CodigoCabysInvalidoException(codigoCabys);
        }
        if (coincidenciaExacta.getImpuesto() == null) {
            throw new CabysSinImpuestoException(codigoCabys);
        }
        return coincidenciaExacta;
    }

    private void aplicarValidacionCabys(Producto producto, CabysDTO cabysValidado) {
        BigDecimal porcentajeImpuesto = BigDecimal.valueOf(cabysValidado.getImpuesto());
        producto.setCodigoCabys(cabysValidado.getCodigo());
        producto.setDescripcionCabys(cabysValidado.getDescripcion());
        producto.setCabysValidadoEn(LocalDateTime.now());
        producto.setPorcentajeImpuesto(porcentajeImpuesto);
        producto.setGravado(porcentajeImpuesto.compareTo(BigDecimal.ZERO) > 0);
    }

    private static <T> void aplicarSiNoEsNulo(T valor, Consumer<T> setter) {
        if (valor != null) {
            setter.accept(valor);
        }
    }
}
