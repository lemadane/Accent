package accent.json.internal;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;

import accent.json.JsonIgnore;
import accent.json.JsonMappingException;
import accent.json.JsonName;
import accent.json.JsonOptions;

public class JsonMapper {
    private final JsonOptions options;

    public JsonMapper(JsonOptions options) {
        this.options = options;
    }

    @SuppressWarnings("unchecked")
    public <T> T mapToRecord(Object jsonObj, Class<T> clazz) {
        if (clazz.isAnnotationPresent(accent.runtime.Singleton.class)) {
            throw new JsonMappingException("Cannot parse into singleton " + clazz.getName() + "; use .instance().");
        }
        if (!clazz.isRecord() && !accent.runtime.AccentModel.class.isAssignableFrom(clazz)) {
            throw new JsonMappingException("Only records and Accent models are supported for typed parsing.");
        }
        if (jsonObj == null) return null;
        if (!clazz.isRecord()) return mapToModel(jsonObj, clazz);
        if (!(jsonObj instanceof Map)) {
            throw new JsonMappingException("Expected JSON object to map to " + clazz.getName());
        }
        Map<String, Object> map = (Map<String, Object>) jsonObj;

        RecordComponent[] components = clazz.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            if (component.isAnnotationPresent(JsonIgnore.class)) {
                args[i] = defaultValue(paramTypes[i]);
                continue;
            }

            String jsonKey = component.getName();
            if (component.isAnnotationPresent(JsonName.class)) {
                jsonKey = component.getAnnotation(JsonName.class).value();
            } else {
                jsonKey = options.namingPolicy().translateName(jsonKey);
            }

            if (map.containsKey(jsonKey)) {
                args[i] = mapValue(map.get(jsonKey), paramTypes[i], component.getGenericType());
            } else {
                args[i] = defaultValue(paramTypes[i]);
            }
        }

        if (!options.ignoreUnknownProperties()) {
            Set<String> knownKeys = new HashSet<>();
            for (RecordComponent component : components) {
                if (component.isAnnotationPresent(JsonIgnore.class)) continue;
                String k = component.isAnnotationPresent(JsonName.class) 
                    ? component.getAnnotation(JsonName.class).value() 
                    : options.namingPolicy().translateName(component.getName());
                knownKeys.add(k);
            }
            for (String key : map.keySet()) {
                if (!knownKeys.contains(key)) {
                    throw new JsonMappingException("Unknown property: " + key + " for record " + clazz.getName());
                }
            }
        }

        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new JsonMappingException("Failed to instantiate record " + clazz.getName(), e);
        }
    }

    private <T> T mapToModel(Object jsonObj, Class<T> clazz) {
        if (!(jsonObj instanceof Map<?, ?> map)) throw new JsonMappingException("Expected object for " + clazz.getName());
        Field[] fields = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()).toArray(Field[]::new);
        Object[] args = new Object[fields.length];
        Class<?>[] types = new Class<?>[fields.length];
        Set<String> known = new HashSet<>();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            types[i] = field.getType();
            String key = field.isAnnotationPresent(JsonName.class) ? field.getAnnotation(JsonName.class).value()
                    : options.namingPolicy().translateName(field.getName());
            known.add(key);
            args[i] = !field.isAnnotationPresent(JsonIgnore.class) && map.containsKey(key)
                    ? mapValue(map.get(key), field.getType(), field.getGenericType()) : defaultValue(field.getType());
        }
        if (!options.ignoreUnknownProperties()) {
            for (Object key : map.keySet()) if (!known.contains(key)) throw new JsonMappingException("Unknown property: " + key);
        }
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor(types);
            if (!ctor.trySetAccessible()) throw new JsonMappingException("Inaccessible model constructor");
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new JsonMappingException("Failed to instantiate model " + clazz.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> mapToList(List<?> list, Class<T> clazz) {
        List<T> result = new ArrayList<>();
        for (Object item : list) {
            result.add(mapToRecord(item, clazz));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object mapValue(Object val, Class<?> type, Type genericType) {
        if (val == null) return null;

        if (type == Optional.class) {
            if (genericType instanceof ParameterizedType pt) {
                Type argType = pt.getActualTypeArguments()[0];
                return Optional.ofNullable(mapValue(val, (Class<?>) getRawType(argType), argType));
            }
            return Optional.of(val);
        }

        if (type.isRecord() || accent.runtime.AccentModel.class.isAssignableFrom(type)) {
            return mapToRecord(val, type);
        }

        if (type.isArray()) {
            if (!(val instanceof List<?> values)) throw new JsonMappingException("Expected array");
            Object array = Array.newInstance(type.getComponentType(), values.size());
            for (int i = 0; i < values.size(); i++) Array.set(array, i, mapValue(values.get(i), type.getComponentType(), type.getComponentType()));
            return array;
        }
        if (type == char.class || type == Character.class) {
            String text = String.valueOf(val);
            if (text.length() != 1) throw new JsonMappingException("Expected one character");
            return text.charAt(0);
        }

        if (type == String.class) {
            return String.valueOf(val);
        }

        if (type == UUID.class) {
            return UUID.fromString(String.valueOf(val));
        }

        if (type == LocalDate.class) return LocalDate.parse(String.valueOf(val));
        if (type == LocalTime.class) return LocalTime.parse(String.valueOf(val));
        if (type == LocalDateTime.class) return LocalDateTime.parse(String.valueOf(val));
        if (type == ZonedDateTime.class) return ZonedDateTime.parse(String.valueOf(val));
        if (type == OffsetDateTime.class) return OffsetDateTime.parse(String.valueOf(val));
        if (type == Instant.class) return Instant.parse(String.valueOf(val));

        if (type.isEnum()) {
            String s = String.valueOf(val);
            for (Object enumConst : type.getEnumConstants()) {
                if (((Enum<?>) enumConst).name().equals(s)) {
                    return enumConst;
                }
            }
            throw new JsonMappingException("Unknown enum value: " + s + " for " + type.getName());
        }

        if (type == boolean.class || type == Boolean.class) {
            if (val instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(val));
        }

        if (type == int.class || type == Integer.class) {
            if (val instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(val));
        }

        if (type == long.class || type == Long.class) {
            if (val instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(val));
        }

        if (type == double.class || type == Double.class) {
            if (val instanceof Number n) return n.doubleValue();
            return Double.parseDouble(String.valueOf(val));
        }

        if (type == float.class || type == Float.class) {
            if (val instanceof Number n) return n.floatValue();
            return Float.parseFloat(String.valueOf(val));
        }

        if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            if (val instanceof List<?> l) {
                Collection<Object> c = Set.class.isAssignableFrom(type) ? new HashSet<>() : new ArrayList<>();
                Type elementType = Object.class;
                if (genericType instanceof ParameterizedType pt) {
                    elementType = pt.getActualTypeArguments()[0];
                }
                for (Object item : l) {
                    c.add(mapValue(item, (Class<?>) getRawType(elementType), elementType));
                }
                return c;
            }
            throw new JsonMappingException("Expected JSON array for collection");
        }

        if (Map.class.isAssignableFrom(type)) {
            if (val instanceof Map<?, ?> m) {
                Map<String, Object> res = new HashMap<>();
                Type valueType = Object.class;
                if (genericType instanceof ParameterizedType pt) {
                    valueType = pt.getActualTypeArguments()[1];
                }
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    res.put(String.valueOf(entry.getKey()), mapValue(entry.getValue(), (Class<?>) getRawType(valueType), valueType));
                }
                return res;
            }
            throw new JsonMappingException("Expected JSON object for map");
        }

        return val;
    }

    private Class<?> getRawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return Object.class;
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == Optional.class) return Optional.empty();
        return null;
    }
}
