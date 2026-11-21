package accent.runtime;

import java.lang.annotation.*;

/** Preserves the required !? marker on a non-null collection with nullable elements. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface NonNullCollection {}
