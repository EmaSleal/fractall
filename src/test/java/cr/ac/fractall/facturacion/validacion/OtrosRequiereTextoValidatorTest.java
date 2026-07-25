package cr.ac.fractall.facturacion.validacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Unit tests for {@link OtrosRequiereTextoValidator}.
 * Covers all 4 cases for the "codigo=99 requires companion text" rule.
 */
class OtrosRequiereTextoValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // Sample annotated bean with one pair
    @OtrosRequiereTexto(codigo = "codigoDescuento", texto = "codigoDescuentoOtro")
    static class DescuentoBean {
        final String codigoDescuento;
        final String codigoDescuentoOtro;

        DescuentoBean(String codigoDescuento, String codigoDescuentoOtro) {
            this.codigoDescuento = codigoDescuento;
            this.codigoDescuentoOtro = codigoDescuentoOtro;
        }

        public String getCodigoDescuento() { return codigoDescuento; }
        public String getCodigoDescuentoOtro() { return codigoDescuentoOtro; }
    }

    // Bean with two @OtrosRequiereTexto constraints (repeatable)
    @OtrosRequiereTexto(codigo = "tipoDocumentoEx1", texto = "tipoDocumentoOtro")
    @OtrosRequiereTexto(codigo = "nombreInstitucion", texto = "nombreInstitucionOtros")
    static class ExoneracionBean {
        final String tipoDocumentoEx1;
        final String tipoDocumentoOtro;
        final String nombreInstitucion;
        final String nombreInstitucionOtros;

        ExoneracionBean(String tipoDocumentoEx1, String tipoDocumentoOtro,
                String nombreInstitucion, String nombreInstitucionOtros) {
            this.tipoDocumentoEx1 = tipoDocumentoEx1;
            this.tipoDocumentoOtro = tipoDocumentoOtro;
            this.nombreInstitucion = nombreInstitucion;
            this.nombreInstitucionOtros = nombreInstitucionOtros;
        }

        public String getTipoDocumentoEx1() { return tipoDocumentoEx1; }
        public String getTipoDocumentoOtro() { return tipoDocumentoOtro; }
        public String getNombreInstitucion() { return nombreInstitucion; }
        public String getNombreInstitucionOtros() { return nombreInstitucionOtros; }
    }

    // Case 1: code != "99" → pass (no violation regardless of companion)
    @Test
    void codigoDistintoDe99PasaSinImportarTexto() {
        DescuentoBean bean = new DescuentoBean("01", null);
        Set<ConstraintViolation<DescuentoBean>> violations = validator.validate(bean);
        assertThat(violations).isEmpty();
    }

    // Case 2: code = "99" and companion text present → pass
    @Test
    void codigo99ConTextoCompanioPasa() {
        DescuentoBean bean = new DescuentoBean("99", "Oferta especial");
        Set<ConstraintViolation<DescuentoBean>> violations = validator.validate(bean);
        assertThat(violations).isEmpty();
    }

    // Case 3: code = "99" and companion text is blank → fail on texto field
    @Test
    void codigo99ConTextoEnBlancoFalla() {
        DescuentoBean bean = new DescuentoBean("99", "   ");
        Set<ConstraintViolation<DescuentoBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(1);
        ConstraintViolation<DescuentoBean> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("codigoDescuentoOtro");
    }

    // Case 4: code = "99" and companion text is null → fail on texto field
    @Test
    void codigo99ConTextoNuloFalla() {
        DescuentoBean bean = new DescuentoBean("99", null);
        Set<ConstraintViolation<DescuentoBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(1);
        ConstraintViolation<DescuentoBean> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("codigoDescuentoOtro");
    }

    // Repeatable: both constraints pass when neither code is "99"
    @Test
    void dosConstraintsAmbosCodigosNoSon99Pasan() {
        ExoneracionBean bean = new ExoneracionBean("08", null, "01", null);
        Set<ConstraintViolation<ExoneracionBean>> violations = validator.validate(bean);
        assertThat(violations).isEmpty();
    }

    // Repeatable: first constraint fails (tipoDocumentoEx1=99 without companion)
    @Test
    void dosConstraintsPrimerCodigo99SinTextoFalla() {
        ExoneracionBean bean = new ExoneracionBean("99", null, "01", null);
        Set<ConstraintViolation<ExoneracionBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("tipoDocumentoOtro");
    }

    // Repeatable: second constraint fails (nombreInstitucion=99 without companion)
    @Test
    void dosConstraintsSegundoCodigo99SinTextoFalla() {
        ExoneracionBean bean = new ExoneracionBean("08", null, "99", null);
        Set<ConstraintViolation<ExoneracionBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("nombreInstitucionOtros");
    }

    // Repeatable: both fail when both codes are "99" without companions
    @Test
    void dosConstraintsAmbosCodigos99SinTextoAmbosFallan() {
        ExoneracionBean bean = new ExoneracionBean("99", null, "99", null);
        Set<ConstraintViolation<ExoneracionBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(2);
    }

    // Custom valorOtros: bean using a different trigger value
    @OtrosRequiereTexto(codigo = "tipo", texto = "tipoOtro", valorOtros = "ZZ")
    static class CustomValorBean {
        final String tipo;
        final String tipoOtro;

        CustomValorBean(String tipo, String tipoOtro) {
            this.tipo = tipo;
            this.tipoOtro = tipoOtro;
        }

        public String getTipo() { return tipo; }
        public String getTipoOtro() { return tipoOtro; }
    }

    @Test
    void valorOtrosPersonalizadoActivaConstraint() {
        CustomValorBean bean = new CustomValorBean("ZZ", null);
        Set<ConstraintViolation<CustomValorBean>> violations = validator.validate(bean);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("tipoOtro");
    }

    @Test
    void valorOtrosPersonalizadoNoActivaSiCodigoNoCoincide() {
        CustomValorBean bean = new CustomValorBean("99", null);
        Set<ConstraintViolation<CustomValorBean>> violations = validator.validate(bean);
        assertThat(violations).isEmpty();
    }
}
