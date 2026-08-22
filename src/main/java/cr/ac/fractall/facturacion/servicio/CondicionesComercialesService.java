package cr.ac.fractall.facturacion.servicio;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cr.ac.fractall.facturacion.modelo.FacturaMedioPago;
import cr.ac.fractall.facturacion.repositorio.FacturaMedioPagoRepository;

/**
 * Validación de condición de venta y síntesis de un único medio de pago -- lógica compartida por
 * {@code FacturaService}, {@code NotaCreditoDebitoService} y {@code TiqueteService}, extraída
 * (hallazgo de code review sobre Fase C, Release 2) de 3 copias casi idénticas de
 * {@code persistirMedioPagoUnico}/{@code validarCondicionVenta}. {@code FacturaService} sigue
 * teniendo lógica propia adicional (lista explícita de medios de pago del request) -- solo la
 * rama de síntesis de UN medio de pago desde {@code medioPago}+{@code total} es la parte
 * compartida.
 */
@Service
public class CondicionesComercialesService {

    private static final String CONDICION_VENTA_CREDITO = "02";

    private final FacturaMedioPagoRepository facturaMedioPagoRepository;

    public CondicionesComercialesService(FacturaMedioPagoRepository facturaMedioPagoRepository) {
        this.facturaMedioPagoRepository = facturaMedioPagoRepository;
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
    public void validarCondicionVenta(String condicionVenta, Integer plazoCredito) {
        if (CONDICION_VENTA_CREDITO.equals(condicionVenta) && plazoCredito == null) {
            throw new CondicionVentaInvalidaException(
                    "plazoCredito es obligatorio cuando condicionVenta = '02' (crédito)");
        }
    }

    /** Un único medio de pago sintetizado por el total del documento. */
    public void persistirMedioPagoUnico(UUID facturaId, String tipoMedioPago, BigDecimal total) {
        FacturaMedioPago medioPago = new FacturaMedioPago();
        medioPago.setFacturaId(facturaId);
        medioPago.setOrden((short) 1);
        medioPago.setTipoMedioPago(tipoMedioPago);
        medioPago.setMedioPagoOtros(null);
        medioPago.setTotalMedioPago(total);
        facturaMedioPagoRepository.save(medioPago);
    }
}
