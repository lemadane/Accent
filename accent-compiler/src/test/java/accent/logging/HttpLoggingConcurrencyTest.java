package accent.logging;

import accent.http.App;
import accent.http.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HttpLoggingConcurrencyTest {
    @TempDir Path directory;

    @Test void reconfigurationDuringConcurrentRequestsDoesNotLoseLogsOrBreakResponses() throws Exception {
        var original = LogManager.configuration();
        var config = new LogConfiguration();
        config.consoleEnabled = false;
        config.fileEnabled = true;
        config.filePath = directory.resolve("requests.log").toString();
        LogManager.configure(config);
        var start = new CountDownLatch(1);
        try (var app = new App(); var client = HttpClient.newHttpClient();
             var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            app.get("/:id", req -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                LogManager.getLogger("http-test").put("id", req.path("id"));
                LogManager.getLogger("http-test").info("request marker");
                LogManager.getLogger("http-test").error("failure marker", new IllegalStateException("trace-" + req.path("id")));
                return Response.text("ok");
            }).listen("127.0.0.1", 0);
            var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 80; i++) {
                responses.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/" + i)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            var reload = tasks.submit(() -> {
                start.await();
                for (int i = 0; i < 80; i++) LogManager.configure(config);
                return null;
            });
            start.countDown();
            for (var response : responses) {
                var result = response.get(15, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode());
                assertEquals("ok", result.body());
            }
            reload.get(10, TimeUnit.SECONDS);
        } finally { LogManager.configure(original); }
        var lines = Files.readAllLines(Path.of(config.filePath));
        for (int i = 0; i < 80; i++) {
            String suffix = "request marker {id=" + i + "}";
            assertEquals(1, lines.stream().filter(line -> line.endsWith(suffix)).count(), suffix);
            String errorSuffix = "failure marker {id=" + i + "}";
            int errorLine = -1;
            for (int line = 0; line < lines.size(); line++) {
                if (lines.get(line).endsWith(errorSuffix)) { errorLine = line; break; }
            }
            assertTrue(errorLine >= 0, errorSuffix);
            assertEquals("java.lang.IllegalStateException: trace-" + i, lines.get(errorLine + 1));
        }
    }

    @Test void reconfigurationWaitsForAnInFlightHttpLogEvent() throws Exception {
        var original = LogManager.configuration();
        var originalOut = System.out;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var configuring = new CountDownLatch(1);
        var config = new LogConfiguration();
        var replacement = new LogConfiguration();
        replacement.consoleEnabled = false;
        try (var capture = new java.io.PrintStream(new java.io.ByteArrayOutputStream()) {
            @Override public void println(String message) {
                if (message.contains("blocked marker")) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("log was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                super.println(message);
            }
        }; var app = new App(); var client = HttpClient.newHttpClient();
             var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            System.setOut(capture);
            LogManager.configure(config);
            app.get("/", req -> {
                LogManager.getLogger("http-test").info("blocked marker");
                return Response.text("ok");
            }).listen("127.0.0.1", 0);
            var response = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reload = tasks.submit(() -> {
                configuring.countDown();
                LogManager.configure(replacement);
            });
            try {
                assertTrue(configuring.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> reload.get(200, TimeUnit.MILLISECONDS),
                        "An appender must not close while an HTTP log event is being written");
            } finally { release.countDown(); }
            assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
            reload.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            System.setOut(originalOut);
            LogManager.configure(original);
        }
    }

}
