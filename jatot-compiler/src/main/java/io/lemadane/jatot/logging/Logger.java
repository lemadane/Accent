package io.lemadane.jatot.logging;

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
}
