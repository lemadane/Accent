package accent.logging.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-thread diagnostic context backing {@link accent.logging.Logger}'s context methods.
 * Internal runtime API: application code should use {@code log.put(...)} etc. instead.
 * Async tasks receive an independent submission-time snapshot.
 */
public final class LogContext {
    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    private LogContext() {}

    public static void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, String> context = CONTEXT.get();
        if (context == null) {
            context = new HashMap<>();
            CONTEXT.set(context);
        }
        context.put(key, value);
    }

    public static String get(String key) {
        Objects.requireNonNull(key, "key");
        Map<String, String> context = CONTEXT.get();
        return context == null ? null : context.get(key);
    }

    public static void remove(String key) {
        Objects.requireNonNull(key, "key");
        Map<String, String> context = CONTEXT.get();
        if (context != null) {
            context.remove(key);
            if (context.isEmpty()) clear();
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** Returns an immutable snapshot, or an empty map when no context exists. */
    public static Map<String, String> snapshot() {
        Map<String, String> context = CONTEXT.get();
        return context == null ? Map.of() : Map.copyOf(context);
    }

    /** Replaces this thread's context with a defensive copy; null entries are rejected. */
    public static void restore(Map<String, String> context) {
        Map<String, String> snapshot = Map.copyOf(Objects.requireNonNull(context, "context"));
        if (snapshot.isEmpty()) clear();
        else CONTEXT.set(new HashMap<>(snapshot));
    }
}
