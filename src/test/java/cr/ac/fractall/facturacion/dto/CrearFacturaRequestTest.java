package cr.ac.fractall.facturacion.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Unit test directo (Bean Validation puro, sin contexto de Spring -- mismo estilo que
 * {@code OtrosRequiereTextoValidatorTest}) del validador {@code isTipoCambioValido} agregado a
 * {@link CrearFacturaRequest}: {@code tipoCambio} es obligatorio para cualquier moneda distinta
 * de CRC/USD (no hay integración real con ninguna otra moneda), pero opcional para CRC (default
 * 1.00000) y USD (se autocompleta desde Hacienda -- ver {@code FacturaService#resolverTipoCambio}).
 */
class CrearFacturaRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static LineaFacturaItemRequest lineaValida() {
        return new LineaFacturaItemRequest(
                UUID.randomUUID(), BigDecimal.ONE, BigDecimal.TEN, null,
                null, null, null, null, null, null, null);
    }

    private static CrearFacturaRequest requestCon(String moneda, BigDecimal tipoCambio) {
        return new CrearFacturaRequest(
                UUID.randomUUID(), null, null, null, null, null,
                null, moneda, tipoCambio, List.of(lineaValida()),
                null, null, null);
    }

    private static Set<ConstraintViolation<CrearFacturaRequest>> violacionesDeTipoCambio(
            Set<ConstraintViolation<CrearFacturaRequest>> violaciones) {
        return violaciones.stream()
                .filter(v -> v.getPropertyPath().toString().equals("tipoCambioValido"))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void monedaEurSinTipoCambioEsInvalida() {
        CrearFacturaRequest request = requestCon("EUR", null);

        Set<ConstraintViolation<CrearFacturaRequest>> violaciones = validator.validate(request);

        assertThat(violacionesDeTipoCambio(violaciones)).hasSize(1);
    }

    @Test
    void monedaUsdSinTipoCambioEsValida() {
        CrearFacturaRequest request = requestCon("USD", null);

        Set<ConstraintViolation<CrearFacturaRequest>> violaciones = validator.validate(request);

        assertThat(violacionesDeTipoCambio(violaciones)).isEmpty();
    }

    @Test
    void monedaCrcSinTipoCambioEsValida() {
        CrearFacturaRequest request = requestCon("CRC", null);

        Set<ConstraintViolation<CrearFacturaRequest>> violaciones = validator.validate(request);

        assertThat(violacionesDeTipoCambio(violaciones)).isEmpty();
    }

    @Test
    void monedaNulaSinTipoCambioEsValida() {
        CrearFacturaRequest request = requestCon(null, null);

        Set<ConstraintViolation<CrearFacturaRequest>> violaciones = validator.validate(request);

        assertThat(violacionesDeTipoCambio(violaciones)).isEmpty();
    }

    @Test
    void monedaEurConTipoCambioExplicitoEsValida() {
        CrearFacturaRequest request = requestCon("EUR", new BigDecimal("600.00"));

        Set<ConstraintViolation<CrearFacturaRequest>> violaciones = validator.validate(request);

        assertThat(violacionesDeTipoCambio(violaciones)).isEmpty();
    }
}
