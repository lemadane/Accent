package accent.http;

/** Wraps the next handler; registration order determines nesting order. */
@FunctionalInterface
public interface Middleware {
    Response handle(Request request, Handler next) throws Exception;
}
