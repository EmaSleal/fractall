package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.catalogo.modelo.Cliente;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearTiqueteRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.fe.TipoComprobantePerfil;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaCodigoComercial;
import cr.ac.fractall.facturacion.modelo.LineaDescuento;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ImpuestoLineaExoneracionRepository;
import cr.ac.fractall.facturacion.repositorio.LineaCodigoComercialRepository;
import cr.ac.fractall.facturacion.repositorio.LineaDescuentoRepository;
import cr.ac.fractall.facturacion.repositorio.LineaFacturaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Orquesta la emisión de Tiquete Electrónico (tipo {@code 04}), Release 2 / Fase C, ver
 * {@code docs/plan-fases-release-2.md}. Más simple que {@link NotaCreditoDebitoService}: sin
 * referencia a ningún documento previo ({@code factura_referencia_id} nunca se setea), sin motivo/
 * código de referencia, y -- a diferencia de TODOS los demás tipos de comprobante -- con cliente
 * OPCIONAL: un Tiquete puede emitirse sin receptor identificado (venta de mostrador, el hallazgo
 * central de esta fase).
 *
 * <p>Estructuralmente es el sibling más simple de {@code FacturaService#crear}: arma líneas desde
 * catálogo vía {@link LineaFacturaEnsamblador} (mismo mecanismo que Nota de Débito) y delega la
 * asignación de consecutivo/clave numérica a {@link ComprobanteEmisionService}, parametrizado con
 * {@link TipoComprobantePerfil#TIQUETE} en ambos casos.
 */
@Service
public class TiqueteService {

    private static final String CONDICION_VENTA_DEFECTO = "01";
    private static final String CONDICION_VENTA_CREDITO = "02";
    private static final String MEDIO_PAGO_DEFECTO = "01";
    private static final String MONEDA_DEFECTO = "CRC";
    private static final BigDecimal TIPO_CAMBIO_DEFECTO = new BigDecimal("1.00000");
    private static final int ESCALA_MONETARIA = 5;

    private final ClienteRepository clienteRepository;
    private final EmpresaRepository empresaRepository;
    private final FacturaRepository facturaRepository;
    private final LineaFacturaRepository lineaFacturaRepository;
    private final LineaCodigoComercialRepository lineaCodigoComercialRepository;
    private final LineaDescuentoRepository lineaDescuentoRepository;
    private final ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository;
    private final FacturaMedioPagoRepository facturaMedioPagoRepository;
    private final LineaFacturaEnsamblador lineaFacturaEnsamblador;
    private final ComprobanteEmisionService comprobanteEmisionService;

    public TiqueteService(
            ClienteRepository clienteRepository,
            EmpresaRepository empresaRepository,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            LineaCodigoComercialRepository lineaCodigoComercialRepository,
            LineaDescuentoRepository lineaDescuentoRepository,
            ImpuestoLineaExoneracionRepository impuestoLineaExoneracionRepository,
            FacturaMedioPagoRepository facturaMedioPagoRepository,
            LineaFacturaEnsamblador lineaFacturaEnsamblador,
            ComprobanteEmisionService comprobanteEmisionService) {
        this.clienteRepository = clienteRepository;
        this.empresaRepository = empresaRepository;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.lineaCodigoComercialRepository = lineaCodigoComercialRepository;
        this.lineaDescuentoRepository = lineaDescuentoRepository;
        this.impuestoLineaExoneracionRepository = impuestoLineaExoneracionRepository;
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
        this.lineaFacturaEnsamblador = lineaFacturaEnsamblador;
        this.comprobanteEmisionService = comprobanteEmisionService;
    }

    @Transactional
    public FacturaResponse crear(CrearTiqueteRequest request) {
        UUID empresaId = TenantContext.get();

        // Cliente OPCIONAL -- el hallazgo central de Fase C. findById ya filtra por @TenantId, así
        // que un id de otro tenant resuelve vacío, tratado igual que "no encontrado" (mismo
        // principio que FacturaService#crear).
        Cliente cliente = null;
        if (request.clienteId() != null) {
            cliente = clienteRepository.findById(request.clienteId())
                    .orElseThrow(() -> new ClienteNoEncontradoException(request.clienteId()));
        }

        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException("Empresa de contexto no encontrada: " + empresaId));

        ZonedDateTime ahoraUtc = ZonedDateTime.now(ZoneOffset.UTC);
        LocalDateTime ahora = ahoraUtc.toLocalDateTime();

        // Armado de líneas desde catálogo delegado a LineaFacturaEnsamblador (mismo mecanismo que
        // ND) -- cliente puede ser null acá; el ensamblador lo tolera salvo que una línea traiga
        // exoneracionId (ver ExoneracionRequiereClienteException).
        LineaFacturaEnsamblador.LineasEnsambladas ensambladas = lineaFacturaEnsamblador.ensamblar(
                request.lineas(), cliente, TipoComprobantePerfil.TIQUETE);
        BigDecimal subtotal = ensambladas.subtotal();
        BigDecimal totalImpuesto = ensambladas.totalImpuesto();

        BigDecimal ivaDevuelto = request.totalIvaDevuelto() != null ? request.totalIvaDevuelto() : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(totalImpuesto)
                .subtract(ivaDevuelto)
                .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        String condicionVenta = request.condicionVenta() != null ? request.condicionVenta() : CONDICION_VENTA_DEFECTO;
        validarCondicionVenta(condicionVenta, request.plazoCredito());

        String medioPago = request.medioPago() != null ? request.medioPago() : MEDIO_PAGO_DEFECTO;
        String moneda = request.moneda() != null ? request.moneda() : MONEDA_DEFECTO;
        BigDecimal tipoCambio = request.tipoCambio() != null ? request.tipoCambio() : TIPO_CAMBIO_DEFECTO;

        Factura tiquete = new Factura();
        tiquete.setClienteId(cliente != null ? cliente.getId() : null);
        tiquete.setCondicionVenta(condicionVenta);
        tiquete.setPlazoCredito(request.plazoCredito());
        tiquete.setMedioPago(medioPago);
        tiquete.setMoneda(moneda);
        tiquete.setTipoCambio(tipoCambio);
        tiquete.setSubtotal(subtotal);
        tiquete.setTotalImpuesto(totalImpuesto);
        tiquete.setTotal(total);
        tiquete.setCondicionVentaOtros(request.condicionVentaOtros());
        tiquete.setTotalIvaDevuelto(ivaDevuelto);
        tiquete.setCreadoPor(resolverUsuarioAutenticado());
        tiquete.setCreateDate(ahora);
        tiquete.setUpdateDate(ahora);
        facturaRepository.saveAndFlush(tiquete);

        lineaFacturaEnsamblador.persistir(tiquete.getId(), ensambladas);

        ComprobanteElectronico comprobante = comprobanteEmisionService.registrarComprobante(
                tiquete, TipoComprobantePerfil.TIQUETE, empresa, ahoraUtc);

        persistirMedioPagoUnico(tiquete.getId(), medioPago, total);

        List<LineaFactura> lineasPersistidas =
                lineaFacturaRepository.findByFacturaIdOrderByNumeroLinea(tiquete.getId());
        String clienteNombre = cliente != null ? cliente.getNombre() : null;
        return construirRespuesta(tiquete, comprobante, lineasPersistidas, clienteNombre);
    }

    /** Mismo requisito que el CHECK de {@code factura} en {@code V4__catalogo_y_facturacion.sql}. */
    private void validarCondicionVenta(String condicionVenta, Integer plazoCredito) {
        if (CONDICION_VENTA_CREDITO.equals(condicionVenta) && plazoCredito == null) {
            throw new CondicionVentaInvalidaException(
                    "plazoCredito es obligatorio cuando condicionVenta = '02' (crédito)");
        }
    }

    /** Un único medio de pago sintetizado por el total del documento -- mismo patrón que
     * {@code NotaCreditoDebitoService#persistirMedioPagoUnico}. */
    private void persistirMedioPagoUnico(UUID facturaId, String tipoMedioPago, BigDecimal total) {
        FacturaMedioPago medioPago = new FacturaMedioPago();
        medioPago.setFacturaId(facturaId);
        medioPago.setOrden((short) 1);
        medioPago.setTipoMedioPago(tipoMedioPago);
        medioPago.setMedioPagoOtros(null);
        medioPago.setTotalMedioPago(total);
        facturaMedioPagoRepository.save(medioPago);
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

        List<FacturaMedioPago> mediosPago =
                facturaMedioPagoRepository.findByFacturaIdOrderByOrden(factura.getId());

        return FacturaResponse.desde(factura, comprobante, lineasResponse, List.of(), List.of(), mediosPago, clienteNombre);
    }

    /**
     * Lee el usuario ya autenticado por {@code JwtAuthenticationFilter} -- mismo patrón duplicado
     * deliberadamente que {@code FacturaService}/{@code NotaCreditoDebitoService} (ver sus
     * javadocs sobre por qué no existe un helper de seguridad compartido en este codebase).
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
