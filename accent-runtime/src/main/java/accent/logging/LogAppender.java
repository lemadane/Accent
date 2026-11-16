package accent.logging;

public interface LogAppender extends AutoCloseable {
    void append(LogEvent event);
    @Override void close();
}
