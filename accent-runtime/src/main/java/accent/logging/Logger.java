package accent.logging;

import accent.logging.internal.LogContext;
import java.util.Map;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Logging and thread-local diagnostic context, available implicitly in Accent. */
public class Logger {
    private static final StackWalker CALLERS = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final String name;

    /** Creates a logger named after the calling class. */
    public Logger() { this(callerName()); }

    public Logger(String name) { this.name = Objects.requireNonNull(name, "name"); }

    private static String callerName() {
        return CALLERS.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != Logger.class).findFirst().orElse(Logger.class).getName());
    }

    /** Logs at INFO under the calling class's name. */
    public static void log(String message) { LogManager.getLogger(callerName()).info(message); }

    public static void log(String message, Throwable throwable) {
        LogManager.getLogger(callerName()).info(message, throwable);
    }

    public String name() { return name; }

    private void log(LogLevel level, String message, Throwable t) {
        if (!isEnabled(level)) return;
        LogEvent event = new LogEvent(Instant.now(), level, name, Thread.currentThread().getName(), message, t);
        LogManager.dispatch(event);
    }

    private boolean isEnabled(LogLevel level) {
        return level.ordinal() >= LogManager.configuration().rootLevel.ordinal();
    }

    public void trace(String message) { log(LogLevel.TRACE, message, null); }
    public void trace(String message, Throwable throwable) { log(LogLevel.TRACE, message, throwable); }
    public void debug(String message) { log(LogLevel.DEBUG, message, null); }
    public void debug(String message, Throwable throwable) { log(LogLevel.DEBUG, message, throwable); }
    public void info(String message) { log(LogLevel.INFO, message, null); }
    public void info(String message, Throwable throwable) { log(LogLevel.INFO, message, throwable); }
    public void warn(String message) { log(LogLevel.WARN, message, null); }
    public void warn(String message, Throwable throwable) { log(LogLevel.WARN, message, throwable); }
    public void error(String message) { log(LogLevel.ERROR, message, null); }
    public void error(String message, Throwable throwable) { log(LogLevel.ERROR, message, throwable); }

    public boolean isTraceEnabled() { return isEnabled(LogLevel.TRACE); }
    public boolean isDebugEnabled() { return isEnabled(LogLevel.DEBUG); }
    public boolean isInfoEnabled() { return isEnabled(LogLevel.INFO); }
    public boolean isWarnEnabled() { return isEnabled(LogLevel.WARN); }
    public boolean isErrorEnabled() { return isEnabled(LogLevel.ERROR); }
    // ---- Diagnostic context (per-thread, shared by all loggers on the thread) ----

    /** Adds a context field attached to every log event on the current thread. Key and value must be non-null. */
    public void put(String key, String value) { LogContext.put(key, value); }

    /** Returns the context value for {@code key}, or null when missing. */
    public String get(String key) { return LogContext.get(key); }

    /** Removes a context field from the current thread. */
    public void remove(String key) { LogContext.remove(key); }

    /** Clears all context fields from the current thread. */
    public void clearContext() { LogContext.clear(); }

    /** MDC-style alias for clearing the current thread's diagnostic context. */
    public void clear() { clearContext(); }

    /** Returns an immutable snapshot of the current thread's context (empty when unset). */
    public Map<String, String> contextMap() { return LogContext.snapshot(); }

    /** Replaces the current thread's context with a defensive copy; null entries are rejected. */
    public void setContextMap(Map<String, String> context) { LogContext.restore(context); }

    /** Runs {@code body} with {@code context} added, then restores the previous context (even on failure). */
    public void withContext(Map<String, String> context, Runnable body) {
        Objects.requireNonNull(body, "body");
        withContext(context, () -> { body.run(); return null; });
    }

    /** Evaluates {@code body} with {@code context} added, then restores the previous context (even on failure). */
    public <T> T withContext(Map<String, String> context, Supplier<T> body) {
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
