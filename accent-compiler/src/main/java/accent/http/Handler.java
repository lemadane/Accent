package accent.http;

/** An HTTP endpoint, executed on a virtual thread. */
@FunctionalInterface
public interface Handler {
    Response handle(Request request) throws Exception;
}
