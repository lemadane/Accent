package accent.compiler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpLoggingEndToEndTest {
    @TempDir static Path directory;
    private static Path output;

    @BeforeAll static void compile() throws Exception {
        Path source = directory.resolve("HttpLogServer.accent");
        Files.writeString(source, """
                import accent.http.App;
                import accent.http.Response;
                import accent.http.HttpException;
                import accent.logging.Logging;

                @Logging
                public class HttpLogServer {
                    public static void background() { log.warn("async marker"); }
                    public static String failAsync() { throw new RuntimeException("async secret"); }
                    public static void main(String[] args) {
                        final app = new App();
                        app.use((req, next) -> {
                            final id = req.header("X-Request-Id");
                            if (id != null) log.put("requestId", id);
                            log.debug("middleware before");
                            if (req.path().equals("/middleware-failure")) throw new RuntimeException("middleware secret");
                            if (req.path().equals("/short")) {
                                log.warn("short marker");
                                return Response.text("denied").status(403);
                            }
                            try { return next.handle(req); }
                            finally { log.debug("middleware after"); }
                        });
                        app.get("/levels", req -> {
                            log.trace("trace marker");
                            log.debug("debug marker");
                            log.info("info marker");
                            log.warn("warn marker");
                            log.error("error marker", new IllegalStateException("stack marker"));
                            final task = async HttpLogServer.background();
                            await task;
                            return Response.text("ok");
                        });
                        app.get("/async-failure", req -> {
                            final task = async HttpLogServer.failAsync();
                            final value = await task;
                            return Response.text(value);
                        });
                        app.get("/short", req -> { throw new RuntimeException("must not execute"); });
                        app.get("/failure", req -> { throw new RuntimeException("handler secret"); });
                        app.get("/expected", req -> { throw new HttpException(422, "invalid customer"); });
                        app.get("/clean", req -> Response.text(log.get("requestId") ?? "empty"));
                        app.listen("127.0.0.1", 0);
                        System.out.println("HTTP_LOG_PORT=" + app.port());
                    }
                }
                """);
        String readme = Files.readString(Path.of("../README.md"));
        String section = readme.split("#### Logging in standalone HTTP", 2)[1];
        String example = section.split("```accent\\n", 2)[1].split("```", 2)[0];
        Path documentation = directory.resolve("Main.accent");
        Files.writeString(documentation, example);
        output = directory.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source, documentation), output,
                List.of(System.getProperty("accent.test.runtimeClasspath").split(java.io.File.pathSeparator)),
                directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @ParameterizedTest
    @CsvSource({"TRACE,true,true", "WARN,true,true", "OFF,true,true", "INFO,false,true", "INFO,true,false"})
    void standaloneHttpHonorsPropertiesLevelsDestinationsAndExceptions(String level, boolean console, boolean file) throws Exception {
        Path working = Files.createTempDirectory(directory, "server-");
        Files.writeString(working.resolve("accent-logging.properties"), "rootLevel=" + level
                + "\nconsoleEnabled=" + console + "\nfileEnabled=" + file + "\nfilePath=http.log\n");
        Path stdout = working.resolve("stdout.log");
        Path stderr = working.resolve("stderr.log");
        String classpath = output + java.io.File.pathSeparator + System.getProperty("accent.test.runtimeClasspath");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "HttpLogServer").directory(working.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try (var client = HttpClient.newHttpClient()) {
            int port = awaitPort(process, stdout, stderr);
            assertEquals("ok", send(client, port, "/levels", "R1").body());
            for (String path : List.of("/failure", "/middleware-failure", "/async-failure")) {
                var response = send(client, port, path, "R2");
                assertEquals(500, response.statusCode());
                assertEquals("Internal Server Error", response.body());
            }
            var denied = send(client, port, "/short", "R4");
            assertEquals(403, denied.statusCode());
            assertEquals("denied", denied.body());
            var expected = send(client, port, "/expected", "R3");
            assertEquals(422, expected.statusCode());
            assertEquals("invalid customer", expected.body());
            assertEquals("empty", send(client, port, "/clean", null).body());
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String out = Files.readString(stdout);
        String err = Files.readString(stderr);
        String disk = file ? Files.readString(working.resolve("http.log")) : "";
        boolean enabled = !level.equals("OFF");
        for (String text : List.of("warn marker", "error marker", "async marker", "stack marker", "handler secret", "middleware secret", "async secret", "short marker")) {
            assertEquals(console && enabled, err.contains(text), "stderr " + text);
            assertEquals(file && enabled, disk.contains(text), "file " + text);
            assertFalse(out.contains(text), "warn/error belong on stderr");
        }
        assertEquals(console && (level.equals("TRACE") || level.equals("INFO")), out.contains("info marker"));
        assertEquals(file && (level.equals("TRACE") || level.equals("INFO")), disk.contains("info marker"));
        for (String text : List.of("trace marker", "debug marker", "middleware before", "middleware after")) {
            assertEquals(console && level.equals("TRACE"), out.contains(text), "stdout " + text);
            assertEquals(file && level.equals("TRACE"), disk.contains(text), "file " + text);
        }
        for (String logs : List.of(console ? err : "", disk)) {
            if (logs.contains("error marker")) {
                assertTrue(logs.lines().anyMatch(line -> line.contains("error marker") && line.endsWith("{requestId=R1}")));
                assertTrue(logs.lines().anyMatch(line -> line.contains("async marker") && line.endsWith("{requestId=R1}")));
                assertEquals(3, logs.lines().filter(line -> line.contains("HTTP request failed") && line.endsWith("{requestId=R2}")).count());
            }
        }
        assertFalse((out + err + disk).contains("must not execute"));
        if (!file) assertFalse(Files.exists(working.resolve("http.log")));
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (id != null) request.header("X-Request-Id", id);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int awaitPort(Process process, Path stdout, Path stderr) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var pattern = java.util.regex.Pattern.compile("HTTP_LOG_PORT=(\\d+)");
        while (System.nanoTime() < deadline) {
            var match = pattern.matcher(Files.readString(stdout));
            if (match.find()) return Integer.parseInt(match.group(1));
            if (!process.isAlive()) fail(Files.readString(stderr));
            Thread.sleep(20);
        }
        throw new AssertionError("Server did not start: " + Files.readString(stderr));
    }
}
