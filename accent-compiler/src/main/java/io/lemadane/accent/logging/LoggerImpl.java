package io.lemadane.accent.logging;
import java.time.Instant;

public class LoggerImpl implements Logger {
    private final String name;

    public LoggerImpl(String name) {
        this.name = name;
    }

    @Override public String name() { return name; }
    
    private void log(LogLevel level, String message, Throwable t) {
        if (!isEnabled(level)) return;
        LogEvent event = new LogEvent(Instant.now(), level, name, Thread.currentThread().getName(), message, t);
        LogManager.dispatch(event);
    }

    private boolean isEnabled(LogLevel level) {
        return level.ordinal() >= LogManager.configuration().rootLevel.ordinal();
    }

    @Override public void trace(String message) { log(LogLevel.TRACE, message, null); }
    @Override public void trace(String message, Throwable throwable) { log(LogLevel.TRACE, message, throwable); }
    @Override public void debug(String message) { log(LogLevel.DEBUG, message, null); }
    @Override public void debug(String message, Throwable throwable) { log(LogLevel.DEBUG, message, throwable); }
    @Override public void info(String message) { log(LogLevel.INFO, message, null); }
    @Override public void info(String message, Throwable throwable) { log(LogLevel.INFO, message, throwable); }
    @Override public void warn(String message) { log(LogLevel.WARN, message, null); }
    @Override public void warn(String message, Throwable throwable) { log(LogLevel.WARN, message, throwable); }
    @Override public void error(String message) { log(LogLevel.ERROR, message, null); }
    @Override public void error(String message, Throwable throwable) { log(LogLevel.ERROR, message, throwable); }
    
    @Override public boolean isTraceEnabled() { return isEnabled(LogLevel.TRACE); }
    @Override public boolean isDebugEnabled() { return isEnabled(LogLevel.DEBUG); }
    @Override public boolean isInfoEnabled() { return isEnabled(LogLevel.INFO); }
    @Override public boolean isWarnEnabled() { return isEnabled(LogLevel.WARN); }
    @Override public boolean isErrorEnabled() { return isEnabled(LogLevel.ERROR); }
}
