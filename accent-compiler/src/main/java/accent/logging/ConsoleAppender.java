package accent.logging;

public class ConsoleAppender implements LogAppender {
    private final LogFormatter formatter;
    
    public ConsoleAppender(LogFormatter formatter) {
        this.formatter = formatter;
    }
    
    @Override
    public void append(LogEvent event) {
        if (event.level() == LogLevel.ERROR || event.level() == LogLevel.WARN) {
            System.err.println(formatter.format(event));
            if (event.throwable() != null) event.throwable().printStackTrace(System.err);
        } else {
            System.out.println(formatter.format(event));
            if (event.throwable() != null) event.throwable().printStackTrace(System.out);
        }
    }
    
    @Override
    public void close() {}
}
