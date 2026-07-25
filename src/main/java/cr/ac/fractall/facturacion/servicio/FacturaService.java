package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.modelo.ClienteExoneracion;
import cr.ac.fractall.catalogo.modelo.Producto;
import cr.ac.fractall.catalogo.modelo.TipoIdentificacion;
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionService;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CodigoComercialRequest;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.DescuentoRequest;
import cr.ac.fractall.facturacion.dto.ExoneracionRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.dto.MedioPagoRequest;
import cr.ac.fractall.facturacion.dto.OtrosCargoRequest;
import cr.ac.fractall.facturacion.dto.ReferenciaRequest;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.FacturaOtrosCargos;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaOtrosCargosRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Orquesta la creación atómica de {@code factura} + {@code linea_factura}(s) +
 * {@code comprobante_electronico} (Fase 7, secciones 4.9 y 4.12-4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}) -- el criterio de salida de la Fase 7 exige
 * que esta orquestación COMPLETA sea segura ante concurrencia, no solo la reclamación aislada del
 * consecutivo: {@link #crear} corre en una única transacción que incluye el bloqueo pesimista de
 * {@code ConsecutivoService#siguienteConsecutivo}, así que un {@code ROLLBACK} en cualquier punto
 * posterior (por ejemplo, un producto que no se encuentra) revierte también el incremento del
 * consecutivo, sin dejar huecos.
 *
 * <p><b>Por qué las validaciones de exoneración se repiten en Java, no solo en los triggers de
 * V10:</b> un {@code RAISE EXCEPTION} de un trigger de Postgres NO se traduce a
 * {@code DataIntegrityViolationException} (pertenece a otra clase de SQLSTATE), así que
 * {@code GlobalExceptionHandler} no lo captura como 409 limpio -- escalaría como un error crudo
 * sin categorizar. Los triggers {@code fn_validar_exoneracion_vigente}/
 * {@code fn_validar_mismo_tenant} (V10) son defensa en profundidad a nivel de motor; las
 * excepciones de dominio limpias que ve el cliente HTTP las lanza este método, ANTES de intentar
 * persistir -- mismo principio ya corregido en revisiones de código de las Fases 5/6.
 */
@Service
public class FacturaService {

    /** Release 1 solo emite Factura Electrónica -- sección 8.1. */
    private static final String TIPO_COMPROBANTE_FACTURA_ELECTRONICA = "01";

    private static final String ESTADO_GENERADO = "GENERADO";

    private static final String CONDICION_VENTA_DEFECTO = "01";
    private static final String CONDICION_VENTA_CREDITO = "02";
    private static final String MEDIO_PAGO_DEFECTO = "01";
    private static final String MONEDA_DEFECTO = "CRC";
    private static final BigDecimal TIPO_CAMBIO_DEFECTO = new BigDecimal("1.00000");
    private static final String TIPO_TRANSACCION_DEFECTO = "01";

    /**
     * Catálogo oficial de 12 tipos de documento de exoneración (sección 4.15.1); estos 4 son
     * exclusivos de Nota de Crédito/Débito y quedan bloqueados para Factura Electrónica.
     */
    private static final Set<String> TIPOS_EXONERACION_EXCLUSIVOS_NC_ND = Set.of("01", "05", "06", "07");

    private static final int ESCALA_MONETARIA = 5;

    private final ClienteRepository clienteRepository;
    private final ProductoRepository productoRepository;
    private final ClienteExoneracionRepository clienteExoneracionRepository;
    private final EmpresaRepository empresaRepository;
    private final ConsecutivoService consecutivoService;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private final FacturaOtrosCargosRepository facturaOtrosCargosRepository;
    private final FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private final FacturaMedioPagoRepository facturaMedioPagoRepository;

    public FacturaService(
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            ClienteExoneracionRepository clienteExoneracionRepository,
            EmpresaRepository empresaRepository,
            ConsecutivoService consecutivoService,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository,
            FacturaOtrosCargosRepository facturaOtrosCargosRepository,
            FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository,
            FacturaMedioPagoRepository facturaMedioPagoRepository) {
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.empresaRepository = empresaRepository;
        this.consecutivoService = consecutivoService;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
        this.facturaOtrosCargosRepository = facturaOtrosCargosRepository;
        this.facturaInformacionReferenciaRepository = facturaInformacionReferenciaRepository;
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
    }

    @Transactional
    public FacturaResponse crear(CrearFacturaRequest request) {
        UUID empresaId = TenantContext.get();

        // findById ya filtra por @TenantId -- un id de otro tenant resuelve vacío, tratado igual
        // que "no encontrado" (mismo principio de ClienteExoneracionService/ProductoService).
        Cliente cliente = clienteRepository.findById(request.clienteId())
                .orElseThrow(() -> new ClienteNoEncontradoException(request.clienteId()));

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException("Empresa de contexto no encontrada: " + empresaId));

        // Validate OtrosCargos IdentificacionTercero.tipo early (before any persistence)
        validarIdentificacionesTerceros(request.otrosCargos());

        LocalDateTime ahora = LocalDateTime.now();

        List<LineaFactura> lineas = new ArrayList<>();
        // Parallel lists to store child data per line (before lines have ids)
        List<List<CodigoComercialRequest>> codigosComerciales = new ArrayList<>();
        List<List<DescuentoRequest>> descuentosPorLinea = new ArrayList<>();
        List<ExoneracionRequest> exoneracionesPorLinea = new ArrayList<>();

        BigDecimal subtotalFactura = BigDecimal.ZERO;
        BigDecimal totalImpuestoFactura = BigDecimal.ZERO;
        int numeroLinea = 1;

        for (LineaFacturaItemRequest item : request.lineas()) {
            Producto producto = productoRepository.findById(item.productoId())
                    .orElseThrow(() -> new ProductoNoEncontradoException(item.productoId()));

            // Compute MontoTotal = precioUnitario * cantidad (before discounts)
            BigDecimal montoTotal = item.cantidad().multiply(item.precioUnitario())
                    .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

            // Compute subtotalLinea = montoTotal - Σ descuentos (taxable base)
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

            // New V11 scalar fields
            linea.setTipoTransaccion(item.tipoTransaccion() != null ? item.tipoTransaccion() : TIPO_TRANSACCION_DEFECTO);
            linea.setUnidadMedidaComercial(item.unidadMedidaComercial());
            linea.setIvaCobradoFabrica(item.ivaCobradoFabrica() != null ? item.ivaCobradoFabrica() : BigDecimal.ZERO);
            linea.setFactorCalculoIva(item.factorCalculoIva());

            BigDecimal montoExoneracionAplicado = BigDecimal.ZERO;

            // Inline exoneracion takes precedence over legacy exoneracionId path
            if (item.exoneracion() != null) {
                // Inline path: montoExoneracion comes from the request block
                montoExoneracionAplicado = item.exoneracion().montoExoneracion() != null
                        ? item.exoneracion().montoExoneracion() : BigDecimal.ZERO;
                // Do NOT set legacy exoneracionId columns for inline block lines
            } else if (item.exoneracionId() != null) {
                montoExoneracionAplicado = aplicarExoneracion(item.exoneracionId(), cliente, linea, impuestoLinea);
            }

            codigosComerciales.add(item.codigosComerciales() != null ? item.codigosComerciales() : List.of());
            descuentosPorLinea.add(item.descuentos() != null ? item.descuentos() : List.of());
            exoneracionesPorLinea.add(item.exoneracion());

            lineas.add(linea);
            subtotalFactura = subtotalFactura.add(subtotalLinea);
            totalImpuestoFactura = totalImpuestoFactura.add(impuestoLinea).subtract(montoExoneracionAplicado);
        }

        BigDecimal totalFactura = subtotalFactura.add(totalImpuestoFactura);

        String condicionVenta = request.condicionVenta() != null ? request.condicionVenta() : CONDICION_VENTA_DEFECTO;
        validarCondicionVenta(condicionVenta, request.plazoCredito());

        // Resolve legacy medioPago for backward compat (single string field on factura table)
        String legacyMedioPago = resolverLegacyMedioPago(request);

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta(condicionVenta);
        factura.setPlazoCredito(request.plazoCredito());
        factura.setMedioPago(legacyMedioPago);
        factura.setMoneda(request.moneda() != null ? request.moneda() : MONEDA_DEFECTO);
        factura.setTipoCambio(request.tipoCambio() != null ? request.tipoCambio() : TIPO_CAMBIO_DEFECTO);
        factura.setSubtotal(subtotalFactura);
        factura.setTotalImpuesto(totalImpuestoFactura);
        factura.setTotal(totalFactura);
        factura.setCreadoPor(resolverUsuarioAutenticado());
        factura.setCreateDate(ahora);
        factura.setUpdateDate(ahora);

        // New V11 factura scalar fields
        factura.setCondicionVentaOtros(request.condicionVentaOtros());
        factura.setCodigoActividadReceptor(request.codigoActividadReceptor());
        factura.setTotalIvaDevuelto(request.totalIvaDevuelto() != null ? request.totalIvaDevuelto() : BigDecimal.ZERO);

        facturaRepository.saveAndFlush(factura);

        // Persist lines (need factura.id first)
        for (LineaFactura linea : lineas) {
            linea.setFacturaId(factura.getId());
        }
        lineaFacturaRepository.saveAll(lineas);
        lineaFacturaRepository.flush();

        // Persist line-level children
        for (int i = 0; i < lineas.size(); i++) {
            LineaFactura linea = lineas.get(i);
            persistirCodigosComerciales(linea.getId(), codigosComerciales.get(i));
            persistirDescuentos(linea.getId(), descuentosPorLinea.get(i));
            persistirExoneracionInline(linea.getId(), exoneracionesPorLinea.get(i));
        }

        // Reclamo del consecutivo DENTRO de la misma transacción que ya escribió factura/líneas
        long numeroConsecutivo = consecutivoService.siguienteConsecutivo(
                empresaId, empresa.getAmbienteHacienda(), TIPO_COMPROBANTE_FACTURA_ELECTRONICA);

        String consecutivoFormateado = ClaveNumericaGenerator.formatearConsecutivo(
                numeroConsecutivo, TIPO_COMPROBANTE_FACTURA_ELECTRONICA);
        String claveNumerica = ClaveNumericaGenerator.generar(
                empresa.getNumeroIdentificacion(), numeroConsecutivo, TIPO_COMPROBANTE_FACTURA_ELECTRONICA, ahora);

        ComprobanteElectronico comprobante = new ComprobanteElectronico();
        comprobante.setFacturaId(factura.getId());
        comprobante.setAmbienteHacienda(empresa.getAmbienteHacienda());
        comprobante.setTipoComprobante(TIPO_COMPROBANTE_FACTURA_ELECTRONICA);
        comprobante.setConsecutivo(consecutivoFormateado);
        comprobante.setClaveNumerica(claveNumerica);
        comprobante.setEstado(ESTADO_GENERADO);
        comprobante.setIntentosEnvio(0);
        comprobante.setFechaEmision(ahora);
        comprobanteElectronicoRepository.saveAndFlush(comprobante);

        // Persist factura-level children (after factura has id)
        persistirOtrosCargos(factura.getId(), request.otrosCargos());
        persistirInformacionReferencia(factura.getId(), request.informacionReferencia());
        persistirMediosPago(factura.getId(), request.mediosPago(), legacyMedioPago, totalFactura);

        // Load children for response
        List<FacturaOtrosCargos> otrosCargosGuardados =
                facturaOtrosCargosRepository.findByFacturaIdOrderByOrden(factura.getId());
        List<FacturaInformacionReferencia> referenciasGuardadas =
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(factura.getId());
        List<FacturaMedioPago> mediosPagoGuardados =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId());

        List<LineaFacturaResponse> lineasResponse = lineas.stream().map(linea -> {
            List<LineaCodigoComercial> codigos =
                    lineaCodigoComercialRepository.findByLineaIdOrderByOrden(linea.getId());
            List<LineaDescuento> descuentosLinea =
                    lineaDescuentoRepository.findByLineaIdOrderByOrden(linea.getId());
            ImpuestoLineaExoneracion exo =
                    impuestoLineaExoneracionRepository.findByLineaId(linea.getId()).orElse(null);
            return LineaFacturaResponse.desde(linea, codigos, descuentosLinea, exo);
        }).toList();
        return FacturaResponse.desde(factura, comprobante, lineasResponse,
                otrosCargosGuardados, referenciasGuardadas, mediosPagoGuardados);
    }

    // =========================================================================
    // Line-level child persistence
    // =========================================================================

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

    // =========================================================================
    // Factura-level child persistence
    // =========================================================================

    private void persistirOtrosCargos(UUID facturaId, List<OtrosCargoRequest> cargos) {
        if (cargos == null || cargos.isEmpty()) return;
        short orden = 1;
        for (OtrosCargoRequest req : cargos) {
            FacturaOtrosCargos entidad = new FacturaOtrosCargos();
            entidad.setFacturaId(facturaId);
            entidad.setOrden(orden++);
            entidad.setTipoDocumentoOc(req.tipoDocumentoOc());
            entidad.setTipoDocumentoOtros(req.tipoDocumentoOtros());
            if (req.identificacionTercero() != null) {
                entidad.setIdentidadTipo(req.identificacionTercero().tipo());
                entidad.setIdentidadNumero(req.identificacionTercero().numero());
            }
            entidad.setNombreTercero(req.nombreTercero());
            entidad.setDetalle(req.detalle());
            entidad.setPorcentajeOc(req.porcentajeOc());
            entidad.setMontoCargo(req.montoCargo());
            facturaOtrosCargosRepository.save(entidad);
        }
    }

    private void persistirInformacionReferencia(UUID facturaId, List<ReferenciaRequest> referencias) {
        if (referencias == null || referencias.isEmpty()) return;
        short orden = 1;
        for (ReferenciaRequest req : referencias) {
            FacturaInformacionReferencia entidad = new FacturaInformacionReferencia();
            entidad.setFacturaId(facturaId);
            entidad.setOrden(orden++);
            entidad.setTipoDocIr(req.tipoDocIr());
            entidad.setTipoDocRefOtro(req.tipoDocRefOtro());
            entidad.setNumero(req.numero());
            entidad.setFechaEmisionIr(req.fechaEmisionIr() != null ? req.fechaEmisionIr().atStartOfDay() : null);
            entidad.setCodigo(req.codigo());
            entidad.setCodigoReferenciaOtro(req.codigoReferenciaOtro());
            entidad.setRazon(req.razon());
            facturaInformacionReferenciaRepository.save(entidad);
        }
    }

    private void persistirMediosPago(UUID facturaId, List<MedioPagoRequest> mediosPago,
            String legacyMedioPago, BigDecimal totalFactura) {
        if (mediosPago != null && !mediosPago.isEmpty()) {
            short orden = 1;
            for (MedioPagoRequest req : mediosPago) {
                FacturaMedioPago entidad = new FacturaMedioPago();
                entidad.setFacturaId(facturaId);
                entidad.setOrden(orden++);
                entidad.setTipoMedioPago(req.tipoMedioPago());
                entidad.setMedioPagoOtros(req.medioPagoOtros());
                entidad.setTotalMedioPago(req.totalMedioPago());
                facturaMedioPagoRepository.save(entidad);
            }
        } else {
            // Legacy fallback: synthesize single payment from legacy medioPago + total
            FacturaMedioPago entidad = new FacturaMedioPago();
            entidad.setFacturaId(facturaId);
            entidad.setOrden((short) 1);
            entidad.setTipoMedioPago(legacyMedioPago != null ? legacyMedioPago : MEDIO_PAGO_DEFECTO);
            entidad.setMedioPagoOtros(null);
            entidad.setTotalMedioPago(totalFactura);
            facturaMedioPagoRepository.save(entidad);
        }
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    private void validarIdentificacionesTerceros(List<OtrosCargoRequest> cargos) {
        if (cargos == null || cargos.isEmpty()) return;
        for (OtrosCargoRequest cargo : cargos) {
            if (cargo.identificacionTercero() != null) {
                String tipo = cargo.identificacionTercero().tipo();
                try {
                    TipoIdentificacion.fromCodigo(tipo);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Tipo de identificación inválido en OtrosCargo.identificacionTercero: " + tipo);
                }
            }
        }
    }

    private String resolverLegacyMedioPago(CrearFacturaRequest request) {
        if (request.mediosPago() != null && !request.mediosPago().isEmpty()) {
            return request.mediosPago().get(0).tipoMedioPago();
        }
        return request.medioPago() != null ? request.medioPago() : MEDIO_PAGO_DEFECTO;
    }

    /**
     * Mismo requisito que ya exige el {@code CHECK} de {@code factura} en
     * {@code V4__catalogo_y_facturacion.sql} ({@code condicion_venta <> '02' OR plazo_credito
     * IS NOT NULL}), validado aquí en Java ANTES de {@code saveAndFlush} -- mismo principio ya
     * aplicado en {@code ClienteService#validarUbicacion}: una violación de ese {@code CHECK} sin
     * validar antes llegaría como {@code DataIntegrityViolationException}, y
     * {@code GlobalExceptionHandler} la traduciría a un 409 genérico de "restricción de
     * unicidad" -- mensaje incorrecto para lo que en realidad es una regla de negocio, no un
     * duplicado.
     */
    private void validarCondicionVenta(String condicionVenta, Integer plazoCredito) {
        if (CONDICION_VENTA_CREDITO.equals(condicionVenta) && plazoCredito == null) {
            throw new CondicionVentaInvalidaException(
                    "plazoCredito es obligatorio cuando condicionVenta = '02' (crédito)");
        }
    }

    /**
     * Verifica y aplica una exoneración a una línea, en este orden (sección 4.15.2): (i)
     * pertenece al mismo cliente de la factura, (ii) está vigente (reusa
     * {@code ClienteExoneracionService#estaVigente}, no la reimplementa), (iii) su
     * {@code tipoDocumento} no es uno de los 4 exclusivos de Nota de Crédito/Débito.
     *
     * <p>Fórmula del monto de exoneración: {@code impuesto * porcentaje / 100} -- una exoneración
     * de Hacienda reduce la carga TRIBUTARIA (el IVA), nunca el precio comercial del bien o
     * servicio; aplicarla sobre subtotal+impuesto en vez de sobre el impuesto solo regalaría
     * también parte del precio base y, en el límite de un 100% de exoneración, podría llevar el
     * impuesto total de la factura a un valor negativo -- una factura no puede declarar impuesto
     * negativo ante Hacienda. Con esta fórmula, {@code montoExoneracionAplicado} queda siempre
     * acotado entre 0 y el propio {@code impuestoLinea}, nunca puede excederlo.
     */
    private BigDecimal aplicarExoneracion(
            UUID exoneracionId, Cliente cliente, LineaFactura linea, BigDecimal impuestoLinea) {
        ClienteExoneracion exoneracion = clienteExoneracionRepository.findById(exoneracionId)
                .orElseThrow(() -> new ClienteExoneracionNoEncontradaException(exoneracionId));

        if (!exoneracion.getClienteId().equals(cliente.getId())) {
            throw new ExoneracionNoPerteneceAlClienteException(exoneracionId, cliente.getId());
        }
        if (!ClienteExoneracionService.estaVigente(exoneracion)) {
            throw new ExoneracionNoVigenteException(exoneracionId);
        }
        if (TIPOS_EXONERACION_EXCLUSIVOS_NC_ND.contains(exoneracion.getTipoDocumento())) {
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

    /**
     * Lee el usuario ya autenticado por {@code JwtAuthenticationFilter} -- mismo patrón que
     * {@code AuthController#usuarioIdAutenticado}, pero lanzando en vez de devolver
     * {@code Optional}: llegar aquí sin autenticar sería un bug de configuración de seguridad
     * (esta ruta ya vive detrás de {@code anyRequest().authenticated()}), no un caso de negocio
     * esperado.
     */
    private UUID resolverUsuarioAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion != null && autenticacion.isAuthenticated()
                && autenticacion.getPrincipal() instanceof UUID usuarioId) {
            return usuarioId;
        }
        throw new IllegalStateException("No hay usuario autenticado en el contexto de seguridad");
    }
}
