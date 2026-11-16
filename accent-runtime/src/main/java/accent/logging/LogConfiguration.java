package accent.logging;
import java.util.Properties;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LogConfiguration {
    public LogLevel rootLevel = LogLevel.INFO;
    public boolean consoleEnabled = true;
    public boolean fileEnabled = false;
    public String filePath = "application.log";
    
    public static LogConfiguration load() {
        LogConfiguration config = new LogConfiguration();
        Properties props = new Properties();
        try {
            if (Files.exists(Paths.get("accent-logging.properties"))) {
                try (InputStream is = new FileInputStream("accent-logging.properties")) {
                    props.load(is);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        
        if (props.containsKey("rootLevel")) {
            config.rootLevel = LogLevel.valueOf(props.getProperty("rootLevel").toUpperCase());
        }
        if (props.containsKey("consoleEnabled")) {
            config.consoleEnabled = Boolean.parseBoolean(props.getProperty("consoleEnabled"));
        }
        if (props.containsKey("fileEnabled")) {
            config.fileEnabled = Boolean.parseBoolean(props.getProperty("fileEnabled"));
        }
        if (props.containsKey("filePath")) {
            config.filePath = props.getProperty("filePath");
        }
        return config;
    }
}
