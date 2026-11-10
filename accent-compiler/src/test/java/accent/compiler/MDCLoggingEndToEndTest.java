package accent.compiler;

import accent.http.App;
import accent.logging.LogConfiguration;
import accent.logging.LogManager;
import accent.logging.Logger;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MDCLoggingEndToEndTest {
    private static final Logger LOG = LogManager.getLogger("mdc-e2e-test");
    @TempDir Path temp;

    @Test void compiledAccentLogsRequestContextAcrossAsyncAndIsolatesConcurrentRequests() throws Exception {
        Path source = temp.resolve("Server.accent");
        Files.writeString(source, """
                import accent.http.App;
                import accent.http.Response;
                import accent.logging.Logging;
                import accent.runtime.Async;

                @Logging
                class Server {
                    static String work() {
                        log.info("async work");
                        return log.get("requestId") else "";
                    }
                    static App start() {
                        final app = new App();
                        app.use((req, next) -> {
                            log.put("requestId", req.path("id"));
                            return next.handle(req);
                        });
                        app.get("/users/:id", req -> {
                            log.info("request work");
                            final task = async static.work();
                            final id = await task;
                            final explicitTask = Async.run(() -> static.work());
                            if (not explicitTask.await().equals(id)) { throw new RuntimeException("context mismatch"); }
                            return Response.text(id);
                        });
                        app.get("/fail/:id", req -> {
                            throw new RuntimeException("expected failure");
                        });
                        app.listen("127.0.0.1", 0); return app;
                    }
                }
                """);
        Path output = temp.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source), output,
                List.of(System.getProperty("accent.test.runtimeClasspath").split(java.io.File.pathSeparator)),
                temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        var original = LogManager.configuration();
        var config = new LogConfiguration();
        config.consoleEnabled = false;
        config.fileEnabled = true;
        Path logFile = temp.resolve("application.log");
        config.filePath = logFile.toString();
        LogManager.configure(config);
        LOG.put("caller", "must-not-leak");
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
             var client = HttpClient.newHttpClient();
             var app = (App) loader.loadClass("Server").getMethod("start").invoke(null)) {
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 12; i++) {
                futures.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/users/R" + i)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            for (int i = 0; i < futures.size(); i++) {
                var response = futures.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals("R" + i, response.body());
            }
            var failure = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/fail/broken")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, failure.statusCode());
            assertEquals("must-not-leak", LOG.get("caller"));
            assertNull(LOG.get("requestId"));
        } finally {
            LOG.clearContext();
            LogManager.configure(original);
        }
        var lines = Files.readAllLines(logFile);
        for (int i = 0; i < 12; i++) {
            String context = "{requestId=R" + i + "}";
            assertEquals(1, lines.stream().filter(line -> line.contains("request work") && line.endsWith(context)).count());
            assertEquals(2, lines.stream().filter(line -> line.contains("async work") && line.endsWith(context)).count());
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains("HTTP request failed") && line.endsWith("{requestId=broken}")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("must-not-leak")));
    }
}
