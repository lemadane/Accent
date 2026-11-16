package accent.runtime;

import java.lang.annotation.*;

/** Marks compiler-managed singleton types; deserializers must not construct copies. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Singleton {}
