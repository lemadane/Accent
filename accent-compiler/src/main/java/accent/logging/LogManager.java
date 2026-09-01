package accent.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LogManager {
    private static LogConfiguration config;
    private static final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private static final List<LogAppender> appenders = new ArrayList<>();
    
    static {
        reload();
    }
    
    public static Logger getLogger(Class<?> type) {
        return getLogger(type.getName());
    }
    
    public static Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, LoggerImpl::new);
    }
    
    public static void configure(LogConfiguration configuration) {
        shutdown();
        config = configuration;
        LogFormatter formatter = new DefaultLogFormatter();
        if (config.consoleEnabled) {
            appenders.add(new ConsoleAppender(formatter));
        }
        if (config.fileEnabled) {
            appenders.add(new FileAppender(config.filePath, formatter));
        }
    }
    
    public static LogConfiguration configuration() {
        return config;
    }
    
    public static void reload() {
        configure(LogConfiguration.load());
    }
    
    public static void shutdown() {
        for (LogAppender appender : appenders) {
            appender.close();
        }
        appenders.clear();
    }
    
    static void dispatch(LogEvent event) {
        for (LogAppender appender : appenders) {
            appender.append(event);
        }
    }
}
