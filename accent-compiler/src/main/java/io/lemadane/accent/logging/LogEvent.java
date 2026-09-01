package io.lemadane.accent.logging;
import java.time.Instant;

public record LogEvent(Instant timestamp, LogLevel level, String loggerName, String threadName, String message, Throwable throwable) {}
