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
import cr.ac.fractall.catalogo.repositorio.ClienteExoneracionRepository;
import cr.ac.fractall.catalogo.repositorio.ClienteRepository;
import cr.ac.fractall.catalogo.repositorio.ProductoRepository;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteExoneracionService;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.dto.LineaFacturaItemRequest;
import cr.ac.fractall.facturacion.dto.LineaFacturaResponse;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.LineaFactura;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
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

    public FacturaService(
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            ClienteExoneracionRepository clienteExoneracionRepository,
            EmpresaRepository empresaRepository,
            ConsecutivoService consecutivoService,
            FacturaRepository facturaRepository,
            LineaFacturaRepository lineaFacturaRepository,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository) {
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.clienteExoneracionRepository = clienteExoneracionRepository;
        this.empresaRepository = empresaRepository;
        this.consecutivoService = consecutivoService;
        this.facturaRepository = facturaRepository;
        this.lineaFacturaRepository = lineaFacturaRepository;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
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

        LocalDateTime ahora = LocalDateTime.now();

        List<LineaFactura> lineas = new ArrayList<>();
        BigDecimal subtotalFactura = BigDecimal.ZERO;
        BigDecimal totalImpuestoFactura = BigDecimal.ZERO;
        int numeroLinea = 1;

        for (LineaFacturaItemRequest item : request.lineas()) {
            Producto producto = productoRepository.findById(item.productoId())
                    .orElseThrow(() -> new ProductoNoEncontradoException(item.productoId()));

            // Snapshot del producto en ESTE momento -- nunca una referencia viva: una
            // corrección posterior al catálogo no debe alterar retroactivamente una línea ya
            // facturada (sección 4.14).
            BigDecimal subtotalLinea = item.cantidad().multiply(item.precioUnitario())
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

            BigDecimal montoExoneracionAplicado = BigDecimal.ZERO;
            if (item.exoneracionId() != null) {
                montoExoneracionAplicado = aplicarExoneracion(item.exoneracionId(), cliente, linea, impuestoLinea);
            }

            lineas.add(linea);
            subtotalFactura = subtotalFactura.add(subtotalLinea);
            totalImpuestoFactura = totalImpuestoFactura.add(impuestoLinea).subtract(montoExoneracionAplicado);
        }

        BigDecimal totalFactura = subtotalFactura.add(totalImpuestoFactura);

        String condicionVenta = request.condicionVenta() != null ? request.condicionVenta() : CONDICION_VENTA_DEFECTO;
        validarCondicionVenta(condicionVenta, request.plazoCredito());

        Factura factura = new Factura();
        factura.setClienteId(cliente.getId());
        factura.setCondicionVenta(condicionVenta);
        factura.setPlazoCredito(request.plazoCredito());
        factura.setMedioPago(request.medioPago() != null ? request.medioPago() : MEDIO_PAGO_DEFECTO);
        factura.setMoneda(request.moneda() != null ? request.moneda() : MONEDA_DEFECTO);
        factura.setTipoCambio(request.tipoCambio() != null ? request.tipoCambio() : TIPO_CAMBIO_DEFECTO);
        factura.setSubtotal(subtotalFactura);
        factura.setTotalImpuesto(totalImpuestoFactura);
        factura.setTotal(totalFactura);
        factura.setCreadoPor(resolverUsuarioAutenticado());
        factura.setCreateDate(ahora);
        factura.setUpdateDate(ahora);
        facturaRepository.saveAndFlush(factura);

        for (LineaFactura linea : lineas) {
            linea.setFacturaId(factura.getId());
        }
        lineaFacturaRepository.saveAll(lineas);
        lineaFacturaRepository.flush();

        // Reclamo del consecutivo DENTRO de la misma transacción que ya escribió factura/líneas
        // -- si algo después de este punto fuerza un ROLLBACK, el incremento se revierte junto
        // con todo lo demás (criterio de salida de la Fase 7, sección 4.9).
        long numeroConsecutivo = consecutivoService.siguienteConsecutivo(
                empresaId, empresa.getAmbienteHacienda(), TIPO_COMPROBANTE_FACTURA_ELECTRONICA);

        // MISMA lógica de formateo para el segmento embebido en claveNumerica y para la columna
        // comprobante_electronico.consecutivo -- ver el javadoc de ClaveNumericaGenerator sobre
        // por qué esto no puede ser dos formateos independientes.
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

        List<LineaFacturaResponse> lineasResponse = lineas.stream().map(LineaFacturaResponse::desde).toList();
        return FacturaResponse.desde(factura, comprobante, lineasResponse);
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
