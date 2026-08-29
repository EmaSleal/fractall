package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.dto.CobroFacturaResponse;
import cr.ac.fractall.facturacion.dto.CobroRegistradoResponse;
import cr.ac.fractall.facturacion.dto.FacturaEstadoCobroResponse;
import cr.ac.fractall.facturacion.dto.HistorialCobrosResponse;
import cr.ac.fractall.facturacion.dto.RegistrarCobroRequest;
import cr.ac.fractall.facturacion.fe.TipoMedioPago;
import cr.ac.fractall.facturacion.modelo.CobroFactura;
import cr.ac.fractall.facturacion.modelo.ComprobanteElectronico;
import cr.ac.fractall.facturacion.modelo.Factura;
import cr.ac.fractall.facturacion.modelo.FacturaEstadoCobro;
import cr.ac.fractall.facturacion.repositorio.CobroFacturaRepository;
import cr.ac.fractall.facturacion.repositorio.ComprobanteElectronicoRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaEstadoCobroRepository;
import cr.ac.fractall.facturacion.repositorio.FacturaRepository;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Orquesta el registro y consulta de cobros de facturas a plazo (Release 3 / Fase C, ver diseño
 * de {@code cobro_factura}). Los triggers de {@code V23} son la autoridad real de cada regla; este
 * servicio las duplica en Java únicamente para producir el contrato HTTP correcto ANTES de
 * intentar el {@code INSERT} -- mismo criterio ya documentado en el javadoc de
 * {@code NotaCreditoDebitoService} y de {@code CondicionesComercialesService}.
 *
 * <p>Orden obligatorio de {@link #registrar}: lock de la factura padre PRIMERO (D6), después
 * alcance/aceptación/medio de pago, después el neteo de NC (D5), y solo entonces el tope y el
 * insert. Con el lock tomado antes de leer, dos transacciones sobre la MISMA factura se
 * serializan, así que el pre-chequeo de la segunda ya observa el cobro committeado de la primera
 * (Postgres READ COMMITTED: cada sentencia posterior al desbloqueo toma un snapshot nuevo).
 */
@Service
public class CobroFacturaService {

    // private, replicando NotaCreditoDebitoService:75 -- la convención establecida en este
    // paquete es una copia privada por servicio, no una constante compartida.
    private static final String ESTADO_ACEPTADO = "ACEPTADO";
    private static final Set<String> CONDICIONES_VENTA_COBRABLES = Set.of("02", "03", "04");

    private final FacturaRepository facturaRepository;
    private final ComprobanteElectronicoRepository comprobanteElectronicoRepository;
    private final CobroFacturaRepository cobroFacturaRepository;
    private final FacturaEstadoCobroRepository facturaEstadoCobroRepository;

    public CobroFacturaService(
            FacturaRepository facturaRepository,
            ComprobanteElectronicoRepository comprobanteElectronicoRepository,
            CobroFacturaRepository cobroFacturaRepository,
            FacturaEstadoCobroRepository facturaEstadoCobroRepository) {
        this.facturaRepository = facturaRepository;
        this.comprobanteElectronicoRepository = comprobanteElectronicoRepository;
        this.cobroFacturaRepository = cobroFacturaRepository;
        this.facturaEstadoCobroRepository = facturaEstadoCobroRepository;
    }

    @Transactional
    public CobroRegistradoResponse registrar(UUID facturaId, RegistrarCobroRequest request) {
        // 1. Lock de la factura padre -- PRIMERA sentencia de la transacción (D6).
        Factura factura = facturaRepository.findWithLockById(facturaId)
                .orElseThrow(() -> new FacturaNoEncontradaException(facturaId));

        // 2. Alcance: solo crédito (02), consignación (03) y apartado (04).
        validarAlcance(facturaId, factura.getCondicionVenta());

        // 3. Estado leído de comprobante_electronico, nunca de factura.
        ComprobanteElectronico comprobante = comprobanteElectronicoRepository.findByFacturaId(facturaId)
                .orElseThrow(() -> new IllegalStateException(
                        "Integridad violada: factura " + facturaId + " no tiene comprobante_electronico"));
        if (!ESTADO_ACEPTADO.equals(comprobante.getEstado())) {
            throw new FacturaOrigenNoAceptadaException(facturaId, comprobante.getEstado());
        }

        // 4. medio_pago validado contra el catálogo FE v4.4.
        String medioPago = request.medioPago();
        try {
            TipoMedioPago.fromCodigo(medioPago);
        } catch (IllegalArgumentException excepcion) {
            throw new MedioPagoInvalidoException(medioPago);
        }

        // 5. Saldo neto: total de la factura menos NC ACEPTADAS (tipo '03') que la referencian (D5).
        UUID empresaId = TenantContext.get();
        BigDecimal totalNc =
                facturaRepository.sumarTotalNotasCreditoAceptadasPorFacturaOrigen(facturaId, empresaId);
        BigDecimal saldoNeto = factura.getTotal().subtract(totalNc);

        // 6-7. Tope: cobros previos + este cobro no puede exceder el saldo neto (> estricto).
        BigDecimal cobrosPrevios = cobroFacturaRepository.sumarMontoCobradoPorFactura(facturaId);
        BigDecimal montoCobrado = request.montoCobrado();
        if (cobrosPrevios.add(montoCobrado).compareTo(saldoNeto) > 0) {
            throw new MontoCobroExcedeSaldoException(facturaId, cobrosPrevios, montoCobrado, saldoNeto);
        }

        // 8. Insert + flush explícito -- ver el javadoc de la clase sobre por qué NO es opcional:
        // la lectura posterior de la vista (paso 9) consulta OTRA tabla, y el auto-flush de
        // Hibernate solo cubre el espacio de consulta de la entidad leída (mismo dispositivo
        // deliberado que ConsecutivoService:24-26).
        LocalDateTime ahora = LocalDateTime.now();
        CobroFactura cobro = new CobroFactura();
        cobro.setFacturaId(facturaId);
        cobro.setMontoCobrado(montoCobrado);
        cobro.setFechaCobro(request.fechaCobro() != null ? request.fechaCobro() : ahora);
        cobro.setMedioPago(medioPago);
        cobro.setReferencia(request.referencia());
        cobro.setRegistradoPor(resolverUsuarioAutenticado());
        cobro.setCreateDate(ahora);
        cobroFacturaRepository.saveAndFlush(cobro);

        // 9. Re-lectura de la proyección DESPUÉS del flush -- load-bearing (ver arriba).
        FacturaEstadoCobro estado = facturaEstadoCobroRepository.findByFacturaId(facturaId)
                .orElseThrow(() -> new IllegalStateException(
                        "Integridad violada: factura " + facturaId + " no tiene proyección factura_estado_cobro"));

        return new CobroRegistradoResponse(CobroFacturaResponse.desde(cobro), FacturaEstadoCobroResponse.desde(estado));
    }

    /** Historial + proyección. Sin lock: no escribe. */
    @Transactional(readOnly = true)
    public HistorialCobrosResponse listar(UUID facturaId) {
        // findById plano (sin lock): distingue "no existe/no es de este tenant" (404) de "existe
        // pero fuera de alcance" (400) -- ver el javadoc de FacturaNoCobrableException. La vista
        // factura_estado_cobro NO sirve para esta distinción: su WHERE ya filtra por
        // condicion_venta IN ('02','03','04'), así que una factura fuera de alcance tampoco
        // aparece ahí, exactamente igual que una factura inexistente.
        Factura factura = facturaRepository.findById(facturaId)
                .orElseThrow(() -> new FacturaNoEncontradaException(facturaId));
        validarAlcance(facturaId, factura.getCondicionVenta());

        FacturaEstadoCobro estado = facturaEstadoCobroRepository.findByFacturaId(facturaId)
                .orElseThrow(() -> new IllegalStateException(
                        "Integridad violada: factura " + facturaId + " no tiene proyección factura_estado_cobro"));

        List<CobroFacturaResponse> cobros = cobroFacturaRepository
                .findByFacturaIdOrderByFechaCobroAscIdAsc(facturaId).stream()
                .map(CobroFacturaResponse::desde)
                .toList();

        return new HistorialCobrosResponse(FacturaEstadoCobroResponse.desde(estado), cobros);
    }

    private void validarAlcance(UUID facturaId, String condicionVenta) {
        if (!CONDICIONES_VENTA_COBRABLES.contains(condicionVenta)) {
            throw new FacturaNoCobrableException(facturaId, condicionVenta);
        }
    }

    /**
     * Lee el usuario ya autenticado por {@code JwtAuthenticationFilter} -- mismo patrón que
     * {@code FacturaService#resolverUsuarioAutenticado}/{@code NotaCreditoDebitoService
     * #resolverUsuarioAutenticado}/{@code TiqueteService#resolverUsuarioAutenticado} (cuarto
     * duplicado deliberado: no existe un helper de seguridad compartido en este codebase, ver
     * {@code NotaCreditoDebitoService:476-481}).
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
