package cr.ac.fractall.facturacion.validacion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Container annotation for repeatable {@link OtrosRequiereTexto}. Generated automatically by the
 * Java compiler when multiple {@code @OtrosRequiereTexto} annotations appear on the same type.
 * Must declare {@code @Constraint} so that Hibernate Validator delegates each member to its own
 * {@link OtrosRequiereTextoValidator} instance.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OtrosRequiereTextosValidator.class)
public @interface OtrosRequiereTextos {

    OtrosRequiereTexto[] value();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
