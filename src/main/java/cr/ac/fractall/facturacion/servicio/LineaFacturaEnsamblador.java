package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionService;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.dto.CodigoComercialRequest;
import cr.ac.fractall.facturacion.dto.DescuentoRequest;
import cr.ac.fractall.facturacion.dto.ExoneracionRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;

/**
 * Extracción compartida de {@code FacturaService.java:342-411} (armado de líneas desde catálogo) y
 * {@code :462-467} (persistencia de sus hijos), Release 2 / Fase B (ver diseño D-E). Vive en
 * {@code facturacion.servicio} en vez de junto a {@code NotaCreditoDebitoService} (Fase 3 de este
 * release) porque AMBAS extracciones -- esta y {@link ComprobanteEmisionService} -- reescriben el
 * mismo método {@code FacturaService#crear}; separarlas en PRs distintos garantizaría un conflicto
 * de rebase en ese único método (ver el diseño, sección de slicing).
 *
 * <p><b>Por qué {@link #ensamblar} recibe {@code perfil}:</b> {@link #aplicarExoneracion} bloquea
 * los 4 tipos de exoneración exclusivos de Nota de Crédito/Débito ({@code 01/05/06/07}, catálogo
 * oficial de 12 tipos, sección 4.15.1) -- una Factura Electrónica nunca puede usarlos, pero una
 * ND/NC SÍ, de hecho existe justamente para eso. El guardia queda condicionado a
 * {@code perfil == FACTURA_ELECTRONICA}: para cualquier otro perfil, esos 4 tipos son válidos.
 */
@Component
class LineaFacturaEnsamblador {

    private static final String TIPO_TRANSACCION_DEFECTO = "01";
    private static final int ESCALA_MONETARIA = 5;

    /**
     * Catálogo oficial de 12 tipos de documento de exoneración (sección 4.15.1); estos 4 son
     * exclusivos de Nota de Crédito/Débito y quedan bloqueados SOLO para Factura Electrónica --
     * ver el javadoc de la clase.
     */
    private static final Set<String> TIPOS_EXONERACION_EXCLUSIVOS_NC_ND = Set.of("01", "05", "06", "07");

    private final ProductoRepository productoRepository;
    private final ClienteExoneracionRepository clienteExoneracionRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;

    LineaFacturaEnsamblador(
            ProductoRepository productoRepository,
            ClienteExoneracionRepository clienteExoneracionRepository,
            LineaFacturaRepository lineaFacturaRepository,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository) {
        this.productoRepository = productoRepository;
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
    }

    /** Salida de {@link #ensamblar}, entrada de {@link #persistir} -- ver diseño D-E. */
    record LineasEnsambladas(
            List<LineaFactura> lineas,
            List<List<CodigoComercialRequest>> codigosComerciales,
            List<List<DescuentoRequest>> descuentos,
            List<ExoneracionRequest> exoneraciones,
            BigDecimal subtotal,
            BigDecimal totalImpuesto) {
    }

    /**
     * Cuerpo verbatim de {@code FacturaService.java:342-411} -- no persiste nada todavía (las
     * líneas aún no tienen {@code facturaId}, ver {@link #persistir}).
     */
    LineasEnsambladas ensamblar(List<LineaFacturaItemRequest> items, Cliente cliente, TipoComprobantePerfil perfil) {
        List<LineaFactura> lineas = new ArrayList<>();
        List<List<CodigoComercialRequest>> codigosComerciales = new ArrayList<>();
        List<List<DescuentoRequest>> descuentosPorLinea = new ArrayList<>();
        List<ExoneracionRequest> exoneracionesPorLinea = new ArrayList<>();

        BigDecimal subtotalFactura = BigDecimal.ZERO;
        BigDecimal totalImpuestoFactura = BigDecimal.ZERO;
        int numeroLinea = 1;

        for (LineaFacturaItemRequest item : items) {
            Producto producto = productoRepository.findById(item.productoId())
                    .orElseThrow(() -> new ProductoNoEncontradoException(item.productoId()));

            BigDecimal montoTotal = item.cantidad().multiply(item.precioUnitario())
                    .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

            BigDecimal totalDescuentosLinea = BigDecimal.ZERO;
            if (item.descuentos() != null) {
                for (DescuentoRequest d : item.descuentos()) {
                    if (d.montoDescuento() != null) {
                        totalDescuentosLinea = totalDescuentosLinea.add(d.montoDescuento());
                    }
                }
            }
            BigDecimal subtotalLinea = montoTotal.subtract(totalDescuentosLinea)
                    .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

            BigDecimal impuestoLinea = subtotalLinea
                    .multiply(producto.getPorcentajeImpuesto())
                    .divide(BigDecimal.valueOf(100), ESCALA_MONETARIA, RoundingMode.HALF_UP);

            LineaFactura linea = new LineaFactura();
            linea.setProductoId(producto.getId());
            linea.setNumeroLinea(numeroLinea++);
            linea.setCantidad(item.cantidad());
            linea.setPrecioUnitario(item.precioUnitario());
            linea.setSubtotal(subtotalLinea);
            linea.setCodigoCabysAplicado(producto.getCodigoCabys());
            linea.setGravadoAplicado(producto.isGravado());
            linea.setPorcentajeImpuestoAplicado(producto.getPorcentajeImpuesto());

            linea.setTipoTransaccion(item.tipoTransaccion() != null ? item.tipoTransaccion() : TIPO_TRANSACCION_DEFECTO);
            linea.setUnidadMedidaComercial(item.unidadMedidaComercial());
            linea.setIvaCobradoFabrica(item.ivaCobradoFabrica() != null ? item.ivaCobradoFabrica() : BigDecimal.ZERO);
            linea.setFactorCalculoIva(item.factorCalculoIva());

            BigDecimal montoExoneracionAplicado = BigDecimal.ZERO;

            // Bloque inline de exoneración tiene precedencia sobre el path legacy de exoneracionId.
            if (item.exoneracion() != null) {
                montoExoneracionAplicado = item.exoneracion().montoExoneracion() != null
                        ? item.exoneracion().montoExoneracion() : BigDecimal.ZERO;
                // No se fijan las columnas legacy de exoneracionId para líneas del bloque inline.
            } else if (item.exoneracionId() != null) {
                montoExoneracionAplicado =
                        aplicarExoneracion(item.exoneracionId(), cliente, linea, impuestoLinea, perfil);
            }

            codigosComerciales.add(item.codigosComerciales() != null ? item.codigosComerciales() : List.of());
            descuentosPorLinea.add(item.descuentos() != null ? item.descuentos() : List.of());
            exoneracionesPorLinea.add(item.exoneracion());

            lineas.add(linea);
            subtotalFactura = subtotalFactura.add(subtotalLinea);
            totalImpuestoFactura = totalImpuestoFactura.add(impuestoLinea).subtract(montoExoneracionAplicado);
        }

        return new LineasEnsambladas(lineas, codigosComerciales, descuentosPorLinea, exoneracionesPorLinea,
                subtotalFactura, totalImpuestoFactura);
    }

    /**
     * Cuerpo verbatim de {@code FacturaService.java:454-467} -- fija {@code facturaId} en cada
     * línea (recién disponible después de que el llamador ya persistió la factura), las guarda, y
     * persiste sus hijos (códigos comerciales, descuentos, exoneración inline).
     */
    void persistir(UUID facturaId, LineasEnsambladas ensambladas) {
        List<LineaFactura> lineas = ensambladas.lineas();
        for (LineaFactura linea : lineas) {
            linea.setFacturaId(facturaId);
        }
        lineaFacturaRepository.saveAll(lineas);
        lineaFacturaRepository.flush();

        for (int i = 0; i < lineas.size(); i++) {
            LineaFactura linea = lineas.get(i);
            persistirCodigosComerciales(linea.getId(), ensambladas.codigosComerciales().get(i));
            persistirDescuentos(linea.getId(), ensambladas.descuentos().get(i));
            persistirExoneracionInline(linea.getId(), ensambladas.exoneraciones().get(i));
        }
    }

    /**
     * Verifica y aplica una exoneración a una línea, en este orden (sección 4.15.2): (i)
     * pertenece al mismo cliente de la factura, (ii) está vigente, (iii) su {@code tipoDocumento}
     * no es uno de los 4 exclusivos de Nota de Crédito/Débito -- SOLO cuando {@code perfil ==
     * FACTURA_ELECTRONICA} (ver el javadoc de la clase).
     */
    private BigDecimal aplicarExoneracion(UUID exoneracionId, Cliente cliente, LineaFactura linea,
            BigDecimal impuestoLinea, TipoComprobantePerfil perfil) {
        ClienteExoneracion exoneracion = clienteExoneracionRepository.findById(exoneracionId)
                .orElseThrow(() -> new ClienteExoneracionNoEncontradaException(exoneracionId));

        if (!exoneracion.getClienteId().equals(cliente.getId())) {
            throw new ExoneracionNoPerteneceAlClienteException(exoneracionId, cliente.getId());
        }
        if (!ClienteExoneracionService.estaVigente(exoneracion)) {
            throw new ExoneracionNoVigenteException(exoneracionId);
        }
        if (perfil == TipoComprobantePerfil.FACTURA_ELECTRONICA
                && TIPOS_EXONERACION_EXCLUSIVOS_NC_ND.contains(exoneracion.getTipoDocumento())) {
            throw new ExoneracionNoAplicableAFacturaElectronicaException(exoneracionId, exoneracion.getTipoDocumento());
        }

        BigDecimal porcentajeExoneracion = exoneracion.getPorcentajeExoneracion();
        BigDecimal montoExoneracionAplicado = impuestoLinea
                .multiply(porcentajeExoneracion)
                .divide(BigDecimal.valueOf(100), ESCALA_MONETARIA, RoundingMode.HALF_UP);

        linea.setExoneracionId(exoneracionId);
        linea.setPorcentajeExoneracionAplicado(porcentajeExoneracion);
        linea.setMontoExoneracionAplicado(montoExoneracionAplicado);
        return montoExoneracionAplicado;
    }

    private void persistirCodigosComerciales(UUID lineaId, List<CodigoComercialRequest> codigos) {
        if (codigos == null || codigos.isEmpty()) return;
        short orden = 1;
        for (CodigoComercialRequest req : codigos) {
            LineaCodigoComercial entidad = new LineaCodigoComercial();
            entidad.setLineaId(lineaId);
            entidad.setOrden(orden++);
            entidad.setTipo(req.tipo());
            entidad.setCodigo(req.codigo());
            lineaCodigoComercialRepository.save(entidad);
        }
    }

    private void persistirDescuentos(UUID lineaId, List<DescuentoRequest> descuentos) {
        if (descuentos == null || descuentos.isEmpty()) return;
        short orden = 1;
        for (DescuentoRequest req : descuentos) {
            LineaDescuento entidad = new LineaDescuento();
            entidad.setLineaId(lineaId);
            entidad.setOrden(orden++);
            entidad.setMontoDescuento(req.montoDescuento());
            entidad.setCodigoDescuento(req.codigoDescuento());
            entidad.setCodigoDescuentoOtro(req.codigoDescuentoOtro());
            entidad.setNaturalezaDescuento(req.naturalezaDescuento());
            lineaDescuentoRepository.save(entidad);
        }
    }

    private void persistirExoneracionInline(UUID lineaId, ExoneracionRequest req) {
        if (req == null) return;
        ImpuestoLineaExoneracion entidad = new ImpuestoLineaExoneracion();
        entidad.setLineaId(lineaId);
        entidad.setTipoDocumentoEx1(req.tipoDocumentoEx1());
        entidad.setTipoDocumentoOtro(req.tipoDocumentoOtro());
        entidad.setNumeroDocumento(req.numeroDocumento());
        entidad.setArticulo(req.articulo());
        entidad.setInciso(req.inciso());
        entidad.setNombreInstitucion(req.nombreInstitucion());
        entidad.setNombreInstitucionOtros(req.nombreInstitucionOtros());
        entidad.setFechaEmisionEx(req.fechaEmisionEx() != null ? req.fechaEmisionEx().atStartOfDay() : null);
        entidad.setTarifaExonerada(req.tarifaExonerada());
        entidad.setMontoExoneracion(req.montoExoneracion() != null ? req.montoExoneracion() : BigDecimal.ZERO);
        impuestoLineaExoneracionRepository.save(entidad);
    }
}
