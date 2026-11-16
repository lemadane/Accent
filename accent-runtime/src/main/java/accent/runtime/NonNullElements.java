package accent.runtime;

import java.lang.annotation.*;

/** A non-null array/list reference with non-null immediate elements. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface NonNullElements {}
