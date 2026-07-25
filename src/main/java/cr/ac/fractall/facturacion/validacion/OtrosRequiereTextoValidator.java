package cr.ac.fractall.facturacion.validacion;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates a single {@link OtrosRequiereTexto} constraint on a bean instance.
 *
 * <p>Reads both properties reflectively via {@link BeanWrapper} (Spring convenience API over
 * {@code PropertyUtils}) and reports a violation on the {@code texto} property path when
 * {@code codigo == valorOtros} and {@code texto} is null or blank.
 */
public class OtrosRequiereTextoValidator implements ConstraintValidator<OtrosRequiereTexto, Object> {

    private String codigoField;
    private String textoField;
    private String valorOtros;

    @Override
    public void initialize(OtrosRequiereTexto annotation) {
        this.codigoField = annotation.codigo();
        this.textoField = annotation.texto();
        this.valorOtros = annotation.valorOtros();
    }

    @Override
    public boolean isValid(Object bean, ConstraintValidatorContext context) {
        if (bean == null) {
            return true;
        }

        BeanWrapper wrapper = new BeanWrapperImpl(bean);

        Object codigoValue = wrapper.getPropertyValue(codigoField);
        if (!valorOtros.equals(codigoValue)) {
            return true;
        }

        Object textoValue = wrapper.getPropertyValue(textoField);
        boolean textoValido = textoValue instanceof String s && !s.isBlank();

        if (!textoValido) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(textoField)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
