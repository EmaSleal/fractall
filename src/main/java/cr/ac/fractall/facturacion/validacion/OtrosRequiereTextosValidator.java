package cr.ac.fractall.facturacion.validacion;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for the {@link OtrosRequiereTextos} container annotation. Delegates each member
 * {@link OtrosRequiereTexto} to an individual {@link OtrosRequiereTextoValidator} instance so that
 * repeatable constraints on the same type all fire and produce independent violations.
 */
public class OtrosRequiereTextosValidator implements ConstraintValidator<OtrosRequiereTextos, Object> {

    private OtrosRequiereTexto[] constraints;

    @Override
    public void initialize(OtrosRequiereTextos annotation) {
        this.constraints = annotation.value();
    }

    @Override
    public boolean isValid(Object bean, ConstraintValidatorContext context) {
        if (bean == null || constraints == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        BeanWrapper wrapper = new BeanWrapperImpl(bean);

        for (OtrosRequiereTexto constraint : constraints) {
            String codigoField = constraint.codigo();
            String textoField = constraint.texto();
            String valorOtros = constraint.valorOtros();

            Object codigoValue = wrapper.getPropertyValue(codigoField);
            if (!valorOtros.equals(codigoValue)) {
                continue;
            }

            Object textoValue = wrapper.getPropertyValue(textoField);
            boolean textoValido = textoValue instanceof String s && !s.isBlank();

            if (!textoValido) {
                context.buildConstraintViolationWithTemplate(constraint.message())
                        .addPropertyNode(textoField)
                        .addConstraintViolation();
                valid = false;
            }
        }

        return valid;
    }
}
