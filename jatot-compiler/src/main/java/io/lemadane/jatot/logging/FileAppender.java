package io.lemadane.jatot.logging;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileAppender implements LogAppender {
    private final LogFormatter formatter;
    private PrintWriter writer;
    
    public FileAppender(String filePath, LogFormatter formatter) {
        this.formatter = formatter;
        try {
            this.writer = new PrintWriter(new FileWriter(filePath, true), true);
        } catch (IOException e) {
            System.err.println("Failed to open file appender: " + e.getMessage());
        }
    }
    
    @Override
    public void append(LogEvent event) {
        if (writer != null) {
            writer.println(formatter.format(event));
            if (event.throwable() != null) {
                event.throwable().printStackTrace(writer);
            }
            writer.flush();
        }
    }
    
    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }
}
