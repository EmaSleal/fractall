package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearNotaCreditoRequest;
import cr.ac.fractall.facturacion.dto.CrearNotaDebitoRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaNotaCreditoRequest;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaInformacionReferencia;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaInformacionReferenciaRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Orquesta la emisión de Nota de Crédito (tipo {@code 03}) y Nota de Débito (tipo {@code 02}),
 * Release 2 / Fase B, ver diseño D-E. Comparte con {@code FacturaService#crear} el mismo motor de
 * asignación de consecutivo/clave (vía {@link ComprobanteEmisionService}) y, para ND, el mismo
 * armado de líneas desde catálogo (vía {@link LineaFacturaEnsamblador}) -- ambas extracciones
 * type-parameterizadas de la Fase B, PR2.
 *
 * <p><b>Por qué las 7 reglas de negocio se validan en Java ANTES de cualquier {@code INSERT}:</b>
 * mismo principio ya documentado en el javadoc de {@code FacturaService} para las validaciones de
 * exoneración -- un {@code RAISE EXCEPTION} de un trigger de Postgres (V18: regla 3 tope de
 * monto, regla 7 tipo de referencia) NO se traduce a {@code DataIntegrityViolationException}, así
 * que sin este pre-chequeo la respuesta HTTP sería un 500 crudo en vez de un 4xx de dominio. Los
 * triggers de V18 quedan como defensa en profundidad, inalcanzables en el camino validado
 * normalmente; solo la carrera de concurrencia entre dos Notas de Crédito que ambas pasan el
 * pre-chequeo antes de que cualquiera haga commit los alcanza -- ese caso lo cubre {@code
 * GlobalExceptionHandler#manejarErrorSqlNoCategorizado} (ver su javadoc), no este servicio.
 *
 * <p>Orden de validación (todas ANTES de escribir nada): regla 7 (origen tipo 01) → regla 1
 * (origen ACEPTADO) → regla 6 (cliente heredado, estructuralmente inescapable: el DTO no expone
 * {@code clienteId}) → regla 2 (línea pertenece al origen, solo NC) → regla 3 por línea (cantidad
 * no excede la línea origen, solo NC) → regla 3 de saldo acumulado (solo NC, después de construir
 * las líneas para conocer el total de la NC actual).
 */
@Slf4j
@Service
public class NotaCreditoDebitoService {

    private static final String ESTADO_ACEPTADO = "ACEPTADO";
    private static final String TIPO_COMPROBANTE_FACTURA_ELECTRONICA = "01";
    private static final String TIPO_DOC_IR_FACTURA_ELECTRONICA = "01";
    private static final int ESCALA_MONETARIA = 5;
    private static final int ESCALA_INTERMEDIA_FACTOR = 10;

    private final ClienteRepository clienteRepository;
    private final EmpresaRepository empresaRepository;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private final FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository;
    private final FacturaMedioPagoRepository facturaMedioPagoRepository;
    private final LineaFacturaEnsamblador lineaFacturaEnsamblador;
    private final ComprobanteEmisionService comprobanteEmisionService;

    public NotaCreditoDebitoService(
            ClienteRepository clienteRepository,
            EmpresaRepository empresaRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository,
            FacturaInformacionReferenciaRepository facturaInformacionReferenciaRepository,
            FacturaMedioPagoRepository facturaMedioPagoRepository,
            LineaFacturaEnsamblador lineaFacturaEnsamblador,
            ComprobanteEmisionService comprobanteEmisionService) {
        this.clienteRepository = clienteRepository;
        this.empresaRepository = empresaRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
        this.facturaInformacionReferenciaRepository = facturaInformacionReferenciaRepository;
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
        this.lineaFacturaEnsamblador = lineaFacturaEnsamblador;
        this.comprobanteEmisionService = comprobanteEmisionService;
    }

    /** Resultado de resolver y validar la factura origen (reglas 7, 1) — compartido por NC y ND. */
    private record OrigenValidado(Factura factura, ComprobanteElectronico comprobante) {}

    private OrigenValidado resolverYValidarOrigen(UUID facturaReferenciaId) {
        Factura origen = facturaRepository.findById(facturaReferenciaId)
                .orElseThrow(() -> new FacturaNoEncontradaException(facturaReferenciaId));

        ComprobanteElectronico origenCe = comprobanteElectronicoRepository.findByFacturaId(origen.getId())
                .orElseThrow(() -> {
                    log.error("Integridad violada: factura {} existe sin comprobante_electronico", origen.getId());
                    return new IllegalStateException(
                            "Integridad violada: factura " + origen.getId() + " no tiene comprobante_electronico");
                });

        // Regla 7 -- el origen debe ser Factura Electrónica, no otra NC/ND.
        if (!TIPO_COMPROBANTE_FACTURA_ELECTRONICA.equals(origenCe.getTipoComprobante())) {
            throw new ReferenciaNoEsFacturaElectronicaException(origen.getId(), origenCe.getTipoComprobante());
        }
        // Regla 1 -- estado leído de comprobante_electronico, nunca de factura.
        if (!ESTADO_ACEPTADO.equals(origenCe.getEstado())) {
            throw new FacturaOrigenNoAceptadaException(origen.getId(), origenCe.getEstado());
        }

        return new OrigenValidado(origen, origenCe);
    }

    @Transactional
    public FacturaResponse crearNotaCredito(CrearNotaCreditoRequest request) {
        UUID empresaId = TenantContext.get();
        OrigenValidado origenValidado = resolverYValidarOrigen(request.facturaReferenciaId());
        Factura origen = origenValidado.factura();
        ComprobanteElectronico origenCe = origenValidado.comprobante();

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException("Empresa de contexto no encontrada: " + empresaId));

        // Regla 6 -- cliente heredado del origen; el DTO no expone clienteId, así que no hay nada
        // que "validar" contra un valor de cliente: es estructuralmente el único origen posible.
        Cliente cliente = clienteRepository.findById(origen.getClienteId())
                .orElseThrow(() -> new ClienteNoEncontradoException(origen.getClienteId()));

        // Regla 2 (línea pertenece al origen) + regla 3 por línea (cantidad no excede el origen).
        List<LineaFactura> lineasOrigen = new ArrayList<>();
        for (LineaNotaCreditoRequest lineaReq : request.lineas()) {
            LineaFactura lineaOrigen = lineaFacturaRepository.findById(lineaReq.lineaFacturaOrigenId())
                    .filter(l -> l.getFacturaId().equals(origen.getId()))
                    .orElseThrow(() -> new LineaOrigenNoPerteneceAFacturaException(
                            lineaReq.lineaFacturaOrigenId(), origen.getId()));
            if (lineaReq.cantidad().compareTo(lineaOrigen.getCantidad()) > 0) {
                throw new CantidadAcreditadaExcedeOrigenException(
                        lineaOrigen.getId(), lineaReq.cantidad(), lineaOrigen.getCantidad());
            }
            lineasOrigen.add(lineaOrigen);
        }

        // Construcción de líneas NC: copia + prorrateo (ver el javadoc de la clase y el diseño D-E).
        List<LineaFactura> lineasNc = new ArrayList<>();
        List<List<LineaCodigoComercial>> codigosPorLinea = new ArrayList<>();
        List<List<LineaDescuento>> descuentosPorLinea = new ArrayList<>();
        List<ImpuestoLineaExoneracion> exoneracionPorLinea = new ArrayList<>();
        BigDecimal subtotalNc = BigDecimal.ZERO;
        BigDecimal totalImpuestoNc = BigDecimal.ZERO;
        int numeroLinea = 1;

        for (int i = 0; i < request.lineas().size(); i++) {
            LineaNotaCreditoRequest lineaReq = request.lineas().get(i);
            LineaFactura origenLinea = lineasOrigen.get(i);

            BigDecimal factor = lineaReq.cantidad()
                    .divide(origenLinea.getCantidad(), ESCALA_INTERMEDIA_FACTOR, RoundingMode.HALF_UP);

            LineaFactura linea = new LineaFactura();
            linea.setProductoId(origenLinea.getProductoId());
            linea.setNumeroLinea(numeroLinea++);
            linea.setCantidad(lineaReq.cantidad());
            linea.setPrecioUnitario(origenLinea.getPrecioUnitario());
            BigDecimal subtotalLinea = origenLinea.getSubtotal()
                    .multiply(factor).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);
            linea.setSubtotal(subtotalLinea);
            linea.setCodigoCabysAplicado(origenLinea.getCodigoCabysAplicado());
            linea.setGravadoAplicado(origenLinea.isGravadoAplicado());
            linea.setPorcentajeImpuestoAplicado(origenLinea.getPorcentajeImpuestoAplicado());
            linea.setTipoTransaccion(origenLinea.getTipoTransaccion());
            linea.setUnidadMedidaComercial(origenLinea.getUnidadMedidaComercial());
            BigDecimal ivaCobradoFabricaOrigen = origenLinea.getIvaCobradoFabrica() != null
                    ? origenLinea.getIvaCobradoFabrica() : BigDecimal.ZERO;
            linea.setIvaCobradoFabrica(
                    ivaCobradoFabricaOrigen.multiply(factor).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP));
            linea.setFactorCalculoIva(origenLinea.getFactorCalculoIva());

            BigDecimal montoExoneracionLinea = BigDecimal.ZERO;
            if (origenLinea.getExoneracionId() != null) {
                linea.setExoneracionId(origenLinea.getExoneracionId());
                linea.setPorcentajeExoneracionAplicado(origenLinea.getPorcentajeExoneracionAplicado());
                BigDecimal montoExoneracionOrigen = origenLinea.getMontoExoneracionAplicado() != null
                        ? origenLinea.getMontoExoneracionAplicado() : BigDecimal.ZERO;
                montoExoneracionLinea = montoExoneracionOrigen
                        .multiply(factor).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);
                linea.setMontoExoneracionAplicado(montoExoneracionLinea);
            }

            BigDecimal impuestoLinea = subtotalLinea
                    .multiply(origenLinea.getPorcentajeImpuestoAplicado())
                    .divide(BigDecimal.valueOf(100), ESCALA_MONETARIA, RoundingMode.HALF_UP);

            lineasNc.add(linea);
            subtotalNc = subtotalNc.add(subtotalLinea);
            totalImpuestoNc = totalImpuestoNc.add(impuestoLinea).subtract(montoExoneracionLinea);

            codigosPorLinea.add(copiarCodigosComerciales(origenLinea.getId()));
            descuentosPorLinea.add(copiarDescuentosProrateados(origenLinea.getId(), factor));
            exoneracionPorLinea.add(copiarExoneracionInlineProrateada(origenLinea.getId(), factor));
        }

        BigDecimal totalNc = subtotalNc.add(totalImpuestoNc).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        // Regla 3 (tope de monto acumulado) -- pre-chequeo en Java ANTES del INSERT (ver el
        // javadoc de la clase y de MontoNotaCreditoExcedeOrigenException).
        BigDecimal sumaNcPrevias = facturaRepository.sumarTotalNotasCreditoPorFacturaOrigen(origen.getId(), empresaId);
        if (sumaNcPrevias.add(totalNc).compareTo(origen.getTotal()) > 0) {
            throw new MontoNotaCreditoExcedeOrigenException(origen.getId(), sumaNcPrevias, totalNc, origen.getTotal());
        }

        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);
        LocalDateTime ahora = ahoraUtc.toLocalDateTime();

        Factura notaCredito = new Factura();
        notaCredito.setClienteId(cliente.getId());
        notaCredito.setCondicionVenta(origen.getCondicionVenta());
        notaCredito.setPlazoCredito(origen.getPlazoCredito());
        notaCredito.setMedioPago(origen.getMedioPago());
        notaCredito.setMoneda(origen.getMoneda());
        notaCredito.setTipoCambio(origen.getTipoCambio());
        notaCredito.setSubtotal(subtotalNc);
        notaCredito.setTotalImpuesto(totalImpuestoNc);
        notaCredito.setTotal(totalNc);
        notaCredito.setCondicionVentaOtros(origen.getCondicionVentaOtros());
        notaCredito.setCodigoActividadReceptor(origen.getCodigoActividadReceptor());
        notaCredito.setTotalIvaDevuelto(BigDecimal.ZERO);
        notaCredito.setFacturaReferenciaId(origen.getId());
        notaCredito.setCreadoPor(resolverUsuarioAutenticado());
        notaCredito.setCreateDate(ahora);
        notaCredito.setUpdateDate(ahora);
        facturaRepository.saveAndFlush(notaCredito);

        for (LineaFactura linea : lineasNc) {
            linea.setFacturaId(notaCredito.getId());
        }
        lineaFacturaRepository.saveAll(lineasNc);
        lineaFacturaRepository.flush();

        for (int i = 0; i < lineasNc.size(); i++) {
            UUID lineaId = lineasNc.get(i).getId();
            persistirCodigosComercialesCopiados(lineaId, codigosPorLinea.get(i));
            persistirDescuentosCopiados(lineaId, descuentosPorLinea.get(i));
            persistirExoneracionCopiada(lineaId, exoneracionPorLinea.get(i));
        }

        ComprobanteElectronico comprobante = comprobanteEmisionService.registrarComprobante(
                notaCredito, TipoComprobantePerfil.NOTA_CREDITO, empresa, ahoraUtc);

        persistirMedioPagoUnico(notaCredito.getId(), origen.getMedioPago(), totalNc);
        persistirInformacionReferenciaDerivada(notaCredito.getId(), origenCe,
                request.codigoReferencia(), request.codigoReferenciaOtro(), request.razon());

        return construirRespuesta(notaCredito, comprobante, lineasNc, cliente.getNombre());
    }

    @Transactional
    public FacturaResponse crearNotaDebito(CrearNotaDebitoRequest request) {
        UUID empresaId = TenantContext.get();
        OrigenValidado origenValidado = resolverYValidarOrigen(request.facturaReferenciaId());
        Factura origen = origenValidado.factura();
        ComprobanteElectronico origenCe = origenValidado.comprobante();

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException("Empresa de contexto no encontrada: " + empresaId));

        // Regla 6 -- cliente heredado del origen (ver el javadoc de la clase).
        Cliente cliente = clienteRepository.findById(origen.getClienteId())
                .orElseThrow(() -> new ClienteNoEncontradoException(origen.getClienteId()));

        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);
        LocalDateTime ahora = ahoraUtc.toLocalDateTime();

        // Armado de líneas desde catálogo -- mismo mecanismo que FacturaService#crear, sin tope
        // de monto (regla 3 no aplica a ND) y sin selección de líneas del origen (a diferencia de
        // NC, ver el javadoc de la clase).
        LineaFacturaEnsamblador.LineasEnsambladas ensambladas = lineaFacturaEnsamblador.ensamblar(
                request.lineas(), cliente, TipoComprobantePerfil.NOTA_DEBITO);
        BigDecimal subtotalNd = ensambladas.subtotal();
        BigDecimal totalImpuestoNd = ensambladas.totalImpuesto();
        BigDecimal totalNd = subtotalNd.add(totalImpuestoNd).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        Factura notaDebito = new Factura();
        notaDebito.setClienteId(cliente.getId());
        notaDebito.setCondicionVenta(origen.getCondicionVenta());
        notaDebito.setPlazoCredito(origen.getPlazoCredito());
        notaDebito.setMedioPago(origen.getMedioPago());
        notaDebito.setMoneda(origen.getMoneda());
        notaDebito.setTipoCambio(origen.getTipoCambio());
        notaDebito.setSubtotal(subtotalNd);
        notaDebito.setTotalImpuesto(totalImpuestoNd);
        notaDebito.setTotal(totalNd);
        notaDebito.setCondicionVentaOtros(origen.getCondicionVentaOtros());
        notaDebito.setCodigoActividadReceptor(origen.getCodigoActividadReceptor());
        notaDebito.setTotalIvaDevuelto(BigDecimal.ZERO);
        notaDebito.setFacturaReferenciaId(origen.getId());
        notaDebito.setCreadoPor(resolverUsuarioAutenticado());
        notaDebito.setCreateDate(ahora);
        notaDebito.setUpdateDate(ahora);
        facturaRepository.saveAndFlush(notaDebito);

        lineaFacturaEnsamblador.persistir(notaDebito.getId(), ensambladas);

        ComprobanteElectronico comprobante = comprobanteEmisionService.registrarComprobante(
                notaDebito, TipoComprobantePerfil.NOTA_DEBITO, empresa, ahoraUtc);

        persistirMedioPagoUnico(notaDebito.getId(), origen.getMedioPago(), totalNd);
        persistirInformacionReferenciaDerivada(notaDebito.getId(), origenCe,
                request.codigoReferencia(), request.codigoReferenciaOtro(), request.razon());

        List<LineaFactura> lineas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(notaDebito.getId());
        return construirRespuesta(notaDebito, comprobante, lineas, cliente.getNombre());
    }

    // =========================================================================
    // Copia de hijos de línea (Nota de Crédito) -- entidades NUEVAS, nunca reutiliza las
    // instancias del origen (guardarlas re-apuntadas corrompería las filas del origen).
    // =========================================================================

    private List<LineaCodigoComercial> copiarCodigosComerciales(UUID lineaOrigenId) {
        return lineaCodigoComercialRepository.findByLineaIdOrderByOrden(lineaOrigenId).stream()
                .map(origen -> {
                    LineaCodigoComercial copia = new LineaCodigoComercial();
                    copia.setOrden(origen.getOrden());
                    copia.setTipo(origen.getTipo());
                    copia.setCodigo(origen.getCodigo());
                    return copia;
                }).toList();
    }

    private List<LineaDescuento> copiarDescuentosProrateados(UUID lineaOrigenId, BigDecimal factor) {
        return lineaDescuentoRepository.findByLineaIdOrderByOrden(lineaOrigenId).stream()
                .map(origen -> {
                    LineaDescuento copia = new LineaDescuento();
                    copia.setOrden(origen.getOrden());
                    copia.setMontoDescuento(
                            origen.getMontoDescuento().multiply(factor).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP));
                    copia.setCodigoDescuento(origen.getCodigoDescuento());
                    copia.setCodigoDescuentoOtro(origen.getCodigoDescuentoOtro());
                    copia.setNaturalezaDescuento(origen.getNaturalezaDescuento());
                    return copia;
                }).toList();
    }

    private ImpuestoLineaExoneracion copiarExoneracionInlineProrateada(UUID lineaOrigenId, BigDecimal factor) {
        return impuestoLineaExoneracionRepository.findByLineaId(lineaOrigenId).map(origen -> {
            ImpuestoLineaExoneracion copia = new ImpuestoLineaExoneracion();
            copia.setTipoDocumentoEx1(origen.getTipoDocumentoEx1());
            copia.setTipoDocumentoOtro(origen.getTipoDocumentoOtro());
            copia.setNumeroDocumento(origen.getNumeroDocumento());
            copia.setArticulo(origen.getArticulo());
            copia.setInciso(origen.getInciso());
            copia.setNombreInstitucion(origen.getNombreInstitucion());
            copia.setNombreInstitucionOtros(origen.getNombreInstitucionOtros());
            copia.setFechaEmisionEx(origen.getFechaEmisionEx());
            copia.setTarifaExonerada(origen.getTarifaExonerada());
            copia.setMontoExoneracion(
                    origen.getMontoExoneracion().multiply(factor).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP));
            return copia;
        }).orElse(null);
    }

    private void persistirCodigosComercialesCopiados(UUID lineaId, List<LineaCodigoComercial> codigos) {
        for (LineaCodigoComercial codigo : codigos) {
            codigo.setLineaId(lineaId);
            lineaCodigoComercialRepository.save(codigo);
        }
    }

    private void persistirDescuentosCopiados(UUID lineaId, List<LineaDescuento> descuentos) {
        for (LineaDescuento descuento : descuentos) {
            descuento.setLineaId(lineaId);
            lineaDescuentoRepository.save(descuento);
        }
    }

    private void persistirExoneracionCopiada(UUID lineaId, ImpuestoLineaExoneracion exoneracion) {
        if (exoneracion == null) return;
        exoneracion.setLineaId(lineaId);
        impuestoLineaExoneracionRepository.save(exoneracion);
    }

    // =========================================================================
    // Factura-level children compartidos por NC y ND
    // =========================================================================

    /** Un único medio de pago sintetizado por el total del documento -- mismo fallback legacy que
     * {@code FacturaService#persistirMediosPago} usa cuando el cliente no envía {@code mediosPago}. */
    private void persistirMedioPagoUnico(UUID facturaId, String tipoMedioPago, BigDecimal total) {
        FacturaMedioPago medioPago = new FacturaMedioPago();
        medioPago.setFacturaId(facturaId);
        medioPago.setOrden((short) 1);
        medioPago.setTipoMedioPago(tipoMedioPago);
        medioPago.setMedioPagoOtros(null);
        medioPago.setTotalMedioPago(total);
        facturaMedioPagoRepository.save(medioPago);
    }

    /** Regla 4 -- numero/fechaEmisionIr derivados del origen, nunca del cliente HTTP. */
    private void persistirInformacionReferenciaDerivada(
            UUID facturaId, ComprobanteElectronico origenCe, String codigo, String codigoReferenciaOtro, String razon) {
        FacturaInformacionReferencia referencia = new FacturaInformacionReferencia();
        referencia.setFacturaId(facturaId);
        referencia.setOrden((short) 1);
        referencia.setTipoDocIr(TIPO_DOC_IR_FACTURA_ELECTRONICA);
        referencia.setNumero(origenCe.getClaveNumerica());
        referencia.setFechaEmisionIr(origenCe.getFechaEmision());
        referencia.setCodigo(codigo);
        referencia.setCodigoReferenciaOtro(codigoReferenciaOtro);
        referencia.setRazon(razon);
        facturaInformacionReferenciaRepository.save(referencia);
    }

    private FacturaResponse construirRespuesta(
            Factura factura, ComprobanteElectronico comprobante, List<LineaFactura> lineas, String clienteNombre) {
        List<LineaFacturaResponse> lineasResponse = lineas.stream().map(linea -> {
            List<LineaCodigoComercial> codigos =
                    lineaCodigoComercialRepository.findByLineaIdOrderByOrden(linea.getId());
            List<LineaDescuento> descuentos =
                    lineaDescuentoRepository.findByLineaIdOrderByOrden(linea.getId());
            ImpuestoLineaExoneracion exoneracion =
                    impuestoLineaExoneracionRepository.findByLineaId(linea.getId()).orElse(null);
            return LineaFacturaResponse.desde(linea, codigos, descuentos, exoneracion);
        }).toList();

        List<FacturaInformacionReferencia> referencias =
                facturaInformacionReferenciaRepository.findByFacturaIdOrderByOrden(factura.getId());
        List<FacturaMedioPago> mediosPago =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId());

        return FacturaResponse.desde(factura, comprobante, lineasResponse, List.of(), referencias, mediosPago, clienteNombre);
    }

    /**
     * Lee el usuario ya autenticado por {@code JwtAuthenticationFilter} -- mismo patrón que
     * {@code FacturaService#resolverUsuarioAutenticado} (duplicado deliberadamente: no existe un
     * helper de seguridad compartido en este codebase, ver también {@code EmpresaController}/
     * {@code AuthController}).
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
