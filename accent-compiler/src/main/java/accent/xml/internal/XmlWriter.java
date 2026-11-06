package accent.xml.internal;

import accent.runtime.DataProperties;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/** XML serialization for records and Accent models. */
public final class XmlWriter {
    private XmlWriter() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        String root = value instanceof Map<?, ?> ? "Map"
                : value instanceof List<?> ? "List"
                : value == null ? "null" : value.getClass().getSimpleName();
        write(root, value, out, active);
        return out.toString();
    }

    private static void write(String name, Object value, StringBuilder out, Set<Object> active) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid XML element name: " + name);
        }
        if (value instanceof Optional<?> optional) value = optional.orElse(null);
        if (value == null) return; // Omit nullable properties, as JSON serialization does.
        out.append('<').append(name).append('>');
        if (value instanceof CharSequence || value instanceof Character || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?> || value instanceof UUID
                || value instanceof TemporalAccessor) {
            escape(value.toString(), out);
        } else {
            if (!active.add(value)) throw new IllegalArgumentException("Circular reference during XML serialization");
            try {
                if (value instanceof Map<?, ?> map) {
                    for (var entry : map.entrySet()) {
                        out.append("<entry>");
                        write("key", String.valueOf(entry.getKey()), out, active);
                        write("value", entry.getValue(), out, active);
                        out.append("</entry>");
                    }
                } else if (value instanceof Iterable<?> items) {
                    for (Object item : items) write("item", item, out, active);
                } else if (value.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(value); i++) write("item", Array.get(value, i), out, active);
                } else {
                    for (var entry : DataProperties.read(value).entrySet()) {
                        write(entry.getKey(), entry.getValue(), out, active);
                    }
                }
            } finally {
                active.remove(value);
            }
        }
        out.append("</").append(name).append('>');
    }

    private static void escape(String value, StringBuilder out) {
        value.codePoints().forEach(c -> {
            if (!(c == 9 || c == 10 || c == 13 || c >= 0x20 && c <= 0xD7FF
                    || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF)) {
                throw new IllegalArgumentException("Invalid XML character: " + c);
            }
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.appendCodePoint(c);
            }
        });
    }
}
