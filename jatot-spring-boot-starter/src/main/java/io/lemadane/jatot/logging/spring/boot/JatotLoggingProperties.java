package io.lemadane.jatot.logging.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jatot.logging")
public class JatotLoggingProperties {
    private String rootLevel = "INFO";
    private boolean consoleEnabled = true;
    private boolean fileEnabled = false;
    private String filePath = "application.log";
    
    public String getRootLevel() { return rootLevel; }
    public void setRootLevel(String rootLevel) { this.rootLevel = rootLevel; }
    
    public boolean isConsoleEnabled() { return consoleEnabled; }
    public void setConsoleEnabled(boolean consoleEnabled) { this.consoleEnabled = consoleEnabled; }
    
    public boolean isFileEnabled() { return fileEnabled; }
    public void setFileEnabled(boolean fileEnabled) { this.fileEnabled = fileEnabled; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
}
