package accent.logging;

import java.time.Instant;
import java.util.Map;

public record LogEvent(Instant timestamp, LogLevel level, String loggerName, String threadName,
                       String message, Throwable throwable, Map<String, String> mdc) {
    public LogEvent {
        mdc = Map.copyOf(mdc);
    }

    public LogEvent(Instant timestamp, LogLevel level, String loggerName, String threadName,
                    String message, Throwable throwable) {
        this(timestamp, level, loggerName, threadName, message, throwable, accent.logging.internal.LogContext.snapshot());
    }
}
