package accent.json;

import java.util.List;
import accent.json.internal.JsonMapper;
import accent.json.internal.JsonParser;
import java.util.Map;
import java.util.LinkedHashMap;

public class Json {
    private final JsonMapper mapper;

    public Json() {
        this(JsonOptions.DEFAULT);
    }

    public Json(JsonOptions options) {
        this.mapper = new JsonMapper(options);
    }

    public static <T> T parse(String json, Class<T> clazz) {
        return new Json().parseInternal(json, clazz);
    }

    public static <T> List<T> parseList(String json, Class<T> clazz) {
        return new Json().parseListInternal(json, clazz);
    }

    /** Untyped JSON objects become Maps, arrays become Lists, and scalars retain their JSON type. */
    public static Object parse(String text) {
        return JsonParser.parse(text);
    }

    public static <T> Map<String, T> parseMap(String text, Class<T> valueType) {
        return new Json().mapParsedMap(parse(text), valueType);
    }

    private <T> Map<String, T> mapParsedMap(Object parsed, Class<T> valueType) {
        if (!(parsed instanceof Map<?, ?> map)) throw new JsonMappingException("Expected JSON object for map");
        Map<String, T> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) result.put((String) entry.getKey(), mapper.mapToRecord(entry.getValue(), valueType));
        return result;
    }

    public static boolean isValid(String json) {
        try {
            JsonParser.parse(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public <T> T parseInternal(String json, Class<T> clazz) {
        Object parsed = JsonParser.parse(json);
        if (clazz == Object.class || clazz == List.class || clazz == Map.class) {
            if (parsed != null && !clazz.isInstance(parsed)) throw new JsonMappingException("Parsed value is not a " + clazz.getSimpleName());
            return clazz.cast(parsed);
        }
        return mapper.mapToRecord(parsed, clazz);
    }

    public <T> List<T> parseListInternal(String json, Class<T> clazz) {
        Object parsed = JsonParser.parse(json);
        if (!(parsed instanceof List)) {
            throw new JsonMappingException("Expected JSON array");
        }
        return mapper.mapToList((List<?>) parsed, clazz);
    }
}
