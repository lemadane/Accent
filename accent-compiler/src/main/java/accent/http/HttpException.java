package accent.http;

/** An intentional client-visible HTTP error. */
public final class HttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public HttpException(int status, String message) {
        super(message);
        if (status < 400 || status > 599) throw new IllegalArgumentException("HTTP error status must be 400..599");
        this.status = status;
    }

    public int status() { return status; }
}
