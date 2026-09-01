package accent.logging.spring.boot;

import accent.logging.LogConfiguration;
import accent.logging.LogLevel;
import accent.logging.LogManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import jakarta.annotation.PostConstruct;

@AutoConfiguration
@EnableConfigurationProperties(AccentLoggingProperties.class)
public class AccentLoggingAutoConfiguration {

    private final AccentLoggingProperties properties;

    public AccentLoggingAutoConfiguration(AccentLoggingProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        LogConfiguration config = new LogConfiguration();
        config.rootLevel = LogLevel.valueOf(properties.getRootLevel().toUpperCase());
        config.consoleEnabled = properties.isConsoleEnabled();
        config.fileEnabled = properties.isFileEnabled();
        config.filePath = properties.getFilePath();
        
        LogManager.configure(config);
    }
}
