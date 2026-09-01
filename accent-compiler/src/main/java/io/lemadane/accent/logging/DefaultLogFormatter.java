package io.lemadane.accent.logging;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DefaultLogFormatter implements LogFormatter {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Override
    public String format(LogEvent event) {
        return String.format("%s %s [%s] %s - %s",
                FORMATTER.format(event.timestamp()),
                event.level(),
                event.threadName(),
                event.loggerName(),
                event.message());
    }
}
