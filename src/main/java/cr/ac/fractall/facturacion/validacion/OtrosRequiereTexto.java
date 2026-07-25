package cr.ac.fractall.facturacion.validacion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Class-level constraint that enforces the "99 = Otros requires companion text" rule used
 * throughout FE v4.4. When the property named by {@link #codigo()} equals {@link #valorOtros()}
 * (default {@code "99"}), the property named by {@link #texto()} must be non-null and non-blank.
 *
 * <p>The constraint is {@link Repeatable} so that multiple field pairs on the same class can each
 * declare their own rule independently.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OtrosRequiereTextoValidator.class)
@Repeatable(OtrosRequiereTextos.class)
public @interface OtrosRequiereTexto {

    /** Property name that holds the categorical code (e.g. {@code "codigoDescuento"}). */
    String codigo();

    /** Property name that must be non-blank when {@link #codigo()} equals {@link #valorOtros()}. */
    String texto();

    /** The trigger value — defaults to {@code "99"}. */
    String valorOtros() default "99";

    String message() default "El campo {texto} es obligatorio cuando {codigo} es '{valorOtros}'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
