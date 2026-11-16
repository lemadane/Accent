package accent.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;

public final class LogManager {
    private static volatile LogConfiguration config;
    private static final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private static final List<LogAppender> appenders = new ArrayList<>();
    private static final ReentrantLock APPENDER_LOCK = new ReentrantLock();
    
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
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(configuration.rootLevel, "rootLevel");
        if (configuration.fileEnabled) Objects.requireNonNull(configuration.filePath, "filePath");
        APPENDER_LOCK.lock();
        try {
            LogFormatter formatter = new DefaultLogFormatter();
            List<LogAppender> replacements = new ArrayList<>();
            if (configuration.consoleEnabled) replacements.add(new ConsoleAppender(formatter));
            if (configuration.fileEnabled) replacements.add(new FileAppender(configuration.filePath, formatter));
            shutdown();
            appenders.addAll(replacements);
            config = configuration;
        } finally {
            APPENDER_LOCK.unlock();
        }
    }

    public static LogConfiguration configuration() {
        return config;
    }
    
    public static void reload() {
        configure(LogConfiguration.load());
    }
    
    public static void shutdown() {
        APPENDER_LOCK.lock();
        try {
            for (LogAppender appender : appenders) appender.close();
            appenders.clear();
        } finally {
            APPENDER_LOCK.unlock();
        }
    }

    static void dispatch(LogEvent event) {
        APPENDER_LOCK.lock();
        try {
            if (event.level().ordinal() < config.rootLevel.ordinal()) return;
            // Keep each event and its stack trace together, including across reconfiguration.
            for (LogAppender appender : appenders) appender.append(event);
        } finally {
            APPENDER_LOCK.unlock();
        }
    }
}
