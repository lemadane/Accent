package accent.http;

import io.undertow.Undertow;
import accent.logging.internal.LogContext;
import accent.logging.LogManager;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.util.HttpString;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standalone Accent HTTP application. Undertow is the default and built-in engine. */
public final class App implements AutoCloseable {
    private final Router router;
    private final List<Middleware> middleware = new ArrayList<>();
    private int maxBodyBytes = 1024 * 1024;
    private Duration shutdownTimeout = Duration.ofSeconds(5);
    private Undertow server;
    private GracefulShutdownHandler graceful;
    private ExecutorService executor;
    private Thread shutdownHook;
    private int port = -1;
    private boolean closed;

    public App() { this(new Router()); }
    public App(Router router) { this.router = Objects.requireNonNull(router, "router"); }
    public Router router() { return router; }
    public App get(String path, Handler handler) { router.get(path, handler); return this; }
    public App post(String path, Handler handler) { router.post(path, handler); return this; }
    public App put(String path, Handler handler) { router.put(path, handler); return this; }
    public App patch(String path, Handler handler) { router.patch(path, handler); return this; }
    public App delete(String path, Handler handler) { router.delete(path, handler); return this; }
    public App head(String path, Handler handler) { router.head(path, handler); return this; }
    public App options(String path, Handler handler) { router.options(path, handler); return this; }
    public App route(String method, String path, Handler handler) { router.route(method, path, handler); return this; }

    public synchronized App use(Middleware value) { configurable(); middleware.add(Objects.requireNonNull(value, "middleware")); return this; }
    public synchronized App maxBodyBytes(int bytes) {
        configurable();
        if (bytes < 1 || bytes == Integer.MAX_VALUE) throw new IllegalArgumentException("Body limit must be 1..2147483646 bytes");
        maxBodyBytes = bytes;
        return this;
    }
    public synchronized App shutdownTimeout(Duration timeout) {
        configurable();
        if (timeout == null || timeout.isNegative()) throw new IllegalArgumentException("Shutdown timeout must be nonnegative");
        timeout.toMillis();
        shutdownTimeout = timeout;
        return this;
    }

    private void configurable() { if (closed || server != null) throw new IllegalStateException("Application is already started or closed"); }

    /** Binds on all interfaces. Use listen(host, port) for an explicit bind address. */
    public App listen(int port) { return listen("0.0.0.0", port); }

    public synchronized App listen(String host, int requestedPort) {
        configurable();
        Objects.requireNonNull(host, "host");
        if (requestedPort < 0 || requestedPort > 65535) throw new IllegalArgumentException("Invalid port");
        router.freeze();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        graceful = new GracefulShutdownHandler(exchange -> exchange.dispatch(executor, () -> handle(exchange)));
        server = Undertow.builder().addHttpListener(requestedPort, host)
                .setIoThreads(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())))
                .setWorkerThreads(8)
                .setServerOption(UndertowOptions.DECODE_URL, false)
                .setHandler(graceful).build();
        try {
            server.start();
            port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
            shutdownHook = new Thread(this::close, "accent-http-shutdown-" + port);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            return this;
        } catch (RuntimeException exception) {
            closed = true;
            server.stop();
            executor.shutdownNow();
            throw exception;
        }
    }

    public synchronized int port() {
        if (port < 0 || closed) throw new IllegalStateException("Application is not listening");
        return port;
    }

    private void handle(HttpServerExchange exchange) {
        LogContext.clear();
        try {
            exchange.startBlocking();
            if (exchange.getRequestContentLength() > maxBodyBytes) throw new HttpException(413, "Request body too large");
            byte[] body = exchange.getInputStream().readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) throw new HttpException(413, "Request body too large");
            String method = exchange.getRequestMethod().toString();
            List<String> segments = Router.decodePath(exchange.getRequestPath());
            Router.Match match = router.match(method, segments);
            var query = new LinkedHashMap<String, List<String>>();
            if (!exchange.getQueryString().isEmpty()) {
                try {
                    for (String pair : exchange.getQueryString().split("&")) {
                        if (pair.isEmpty()) continue;
                        String[] parts = pair.split("=", 2);
                        String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                        String value = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        query.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                    }
                } catch (IllegalArgumentException exception) { throw new HttpException(400, "Invalid URL query"); }
            }
            var headers = new LinkedHashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach(values -> headers.put(values.getHeaderName().toString().toLowerCase(Locale.ROOT), List.copyOf(values)));
            Request request = new Request(method, "/" + String.join("/", segments), match.parameters(), query, headers, body);
            Handler selected = match.handler();
            Handler chain = req -> normalize(selected.handle(req));
            for (int i = middleware.size() - 1; i >= 0; i--) {
                Middleware current = middleware.get(i);
                Handler next = chain;
                chain = req -> normalize(current.handle(req, next));
            }
            Response response = chain.handle(request);
            write(exchange, response == null ? Response.empty(204) : response);
        } catch (HttpException exception) {
            exchange.setPersistent(false);
            error(exchange, exception.status(), exception.getMessage());
        } catch (Exception exception) {
            LogManager.getLogger(App.class).error("HTTP request failed", exception);
            error(exchange, 500, "Internal Server Error");
        } finally {
            LogContext.clear();
            exchange.endExchange();
        }
    }

    private static Response normalize(Response response) { return response == null ? Response.empty(204) : response; }

    private static void error(HttpServerExchange exchange, int status, String message) {
        if (!exchange.isResponseStarted()) {
            exchange.getResponseHeaders().clear();
            try { write(exchange, Response.text(message).status(status)); }
            catch (IOException exception) { exchange.setPersistent(false); }
        } else { exchange.setPersistent(false); }
    }

    private static void write(HttpServerExchange exchange, Response response) throws IOException {
        exchange.setStatusCode(response.status());
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().put(new HttpString(name), value));
        byte[] body = response.content();
        if (response.status() == 204 || response.status() == 205 || response.status() == 304) {
            exchange.setResponseContentLength(0);
        } else {
            exchange.setResponseContentLength(body.length);
            if (!exchange.getRequestMethod().toString().equals("HEAD")) exchange.getOutputStream().write(body);
        }
    }

    /** Stops accepting requests, waits for active exchanges, then releases server resources. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (server == null) { router.freeze(); return; }
        }
        try {
            graceful.shutdown();
            graceful.awaitShutdown(shutdownTimeout.toMillis());
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        finally {
            server.stop();
            executor.shutdownNow();
            if (Thread.currentThread() != shutdownHook) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException exception) { /* JVM shutdown is already in progress. */ }
            }
        }
    }
}
