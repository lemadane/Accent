package accent.json.internal;

import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.*;

import accent.json.JsonIgnore;
import accent.json.JsonName;
import accent.json.JsonOptions;

public class JsonStringifier {
    private final JsonOptions options;

    public JsonStringifier(JsonOptions options) {
        this.options = options;
    }

    public String stringify(Object obj) {
        StringBuilder sb = new StringBuilder();
        stringifyInternal(obj, sb, 0);
        return sb.toString();
    }

    private void stringifyInternal(Object obj, StringBuilder sb, int depth) {
        if (obj == null) {
            sb.append("null");
            return;
        }

        if (obj instanceof Optional<?> opt) {
            stringifyInternal(opt.orElse(null), sb, depth);
            return;
        }

        if (obj instanceof String || obj instanceof UUID || obj instanceof Enum || obj instanceof TemporalAccessor) {
            sb.append('"');
            escapeString(obj.toString(), sb);
            sb.append('"');
            return;
        }

        if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj.toString());
            return;
        }

        if (obj instanceof Collection<?> coll) {
            sb.append('[');
            boolean first = true;
            for (Object item : coll) {
                if (!first) sb.append(',');
                first = false;
                if (options.prettyPrint()) {
                    sb.append("\n");
                    indent(sb, depth + 1);
                }
                stringifyInternal(item, sb, depth + 1);
            }
            if (options.prettyPrint() && !coll.isEmpty()) {
                sb.append("\n");
                indent(sb, depth);
            }
            sb.append(']');
            return;
        }

        if (obj instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (options.prettyPrint()) {
                    sb.append("\n");
                    indent(sb, depth + 1);
                }
                sb.append('"');
                escapeString(String.valueOf(entry.getKey()), sb);
                sb.append("\":");
                if (options.prettyPrint()) sb.append(" ");
                stringifyInternal(entry.getValue(), sb, depth + 1);
            }
            if (options.prettyPrint() && !map.isEmpty()) {
                sb.append("\n");
                indent(sb, depth);
            }
            sb.append('}');
            return;
        }

        if (obj.getClass().isRecord()) {
            sb.append('{');
            boolean first = true;
            try {
                for (RecordComponent comp : obj.getClass().getRecordComponents()) {
                    if (comp.isAnnotationPresent(JsonIgnore.class)) continue;
                    
                    Object val = comp.getAccessor().invoke(obj);
                    if (val == null) continue; // Skip nulls or keep? Usually skip or explicit. Let's keep for simplicity.
                    
                    if (!first) sb.append(',');
                    first = false;
                    
                    if (options.prettyPrint()) {
                        sb.append("\n");
                        indent(sb, depth + 1);
                    }
                    
                    String key = comp.getName();
                    if (comp.isAnnotationPresent(JsonName.class)) {
                        key = comp.getAnnotation(JsonName.class).value();
                    } else {
                        key = options.namingPolicy().translateName(key);
                    }
                    
                    sb.append('"');
                    escapeString(key, sb);
                    sb.append("\":");
                    if (options.prettyPrint()) sb.append(" ");
                    stringifyInternal(val, sb, depth + 1);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to stringify record", e);
            }
            if (options.prettyPrint() && !first) {
                sb.append("\n");
                indent(sb, depth);
            }
            sb.append('}');
            return;
        }

        throw new IllegalArgumentException("Unsupported type for stringification: " + obj.getClass());
    }

    private void escapeString(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth * 2; i++) {
            sb.append(' ');
        }
    }
}
