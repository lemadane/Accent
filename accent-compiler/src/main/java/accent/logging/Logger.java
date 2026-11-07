package accent.logging;

import accent.logging.internal.LogContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public interface Logger {
    String name();
    void trace(String message);
    void trace(String message, Throwable throwable);
    void debug(String message);
    void debug(String message, Throwable throwable);
    void info(String message);
    void info(String message, Throwable throwable);
    void warn(String message);
    void warn(String message, Throwable throwable);
    void error(String message);
    void error(String message, Throwable throwable);

    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();

    // ---- Diagnostic context (per-thread, shared by all loggers on the thread) ----

    /** Adds a context field attached to every log event on the current thread. Key and value must be non-null. */
    default void put(String key, String value) { LogContext.put(key, value); }

    /** Returns the context value for {@code key}, or null when missing. */
    default String get(String key) { return LogContext.get(key); }

    /** Removes a context field from the current thread. */
    default void remove(String key) { LogContext.remove(key); }

    /** Clears all context fields from the current thread. */
    default void clearContext() { LogContext.clear(); }

    /** Returns an immutable snapshot of the current thread's context (empty when unset). */
    default Map<String, String> contextMap() { return LogContext.snapshot(); }

    /** Replaces the current thread's context with a defensive copy; null entries are rejected. */
    default void setContextMap(Map<String, String> context) { LogContext.restore(context); }

    /** Runs {@code body} with {@code context} added, then restores the previous context (even on failure). */
    default void withContext(Map<String, String> context, Runnable body) {
        Objects.requireNonNull(body, "body");
        withContext(context, () -> { body.run(); return null; });
    }

    /** Evaluates {@code body} with {@code context} added, then restores the previous context (even on failure). */
    default <T> T withContext(Map<String, String> context, Supplier<T> body) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(body, "body");
        Map<String, String> previous = LogContext.snapshot();
        context.forEach(LogContext::put);
        try {
            return body.get();
        } finally {
            LogContext.restore(previous);
        }
    }
}
