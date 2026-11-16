package accent.runtime;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads declared data, without invoking user-defined serialization methods. */
public final class DataProperties {
    private DataProperties() {}

    public static Map<String, Object> read(Object value) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            if (value.getClass().isRecord()) {
                for (var component : value.getClass().getRecordComponents()) {
                    var accessor = component.getAccessor();
                    if (!accessor.trySetAccessible()) throw new IllegalArgumentException("Inaccessible record component");
                    properties.put(component.getName(), accessor.invoke(value));
                }
            } else if (value instanceof AccentModel) {
                for (var field : value.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    if (!field.trySetAccessible()) throw new IllegalArgumentException("Inaccessible model field");
                    properties.put(field.getName(), field.get(value));
                }
            } else {
                throw new IllegalArgumentException("Expected an Accent model or record: " + value.getClass());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot read data properties", e);
        }
        return properties;
    }
}
