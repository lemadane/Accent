package io.lemadane.accent.json;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonIgnore {
}
