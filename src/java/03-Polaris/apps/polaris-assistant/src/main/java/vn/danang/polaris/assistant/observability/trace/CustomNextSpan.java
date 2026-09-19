package vn.danang.polaris.assistant.observability.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Creates and activates a distributed tracing child span for the duration of the annotated method.
 * Supports defining tags statically or dynamically bound to method arguments via SpEL expressions.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CustomNextSpan {

    /**
     * The name of the span. Alias for {@link #name()}.
     */
    String value() default "";

    /**
     * The name of the span.
     */
    String name() default "";

    /**
     * Tags to attach to the span. Can be static values or SpEL expressions evaluated against method arguments.
     */
    SpanTag[] tags() default {};
}
