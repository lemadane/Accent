package accent.http;

import accent.compiler.AccentCompiler;
import accent.json.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets, real Undertow, and real compiled Accent endpoints. */
@Timeout(20)
class HttpEndToEndTest {
    @TempDir static Path directory;
    private static HttpClient client;
    private static URLClassLoader loader;
    private static Class<?> fixture;

    @BeforeAll
    static void compileAccentServer() throws Exception {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Path source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("User.accent"), "package httpfixture; public record User(String id, String name) {}");
        Files.writeString(source.resolve("Card.accent"), """
                package httpfixture;
                import accent.html.Component;
                import accent.html.Html;
                public record Card(String name) implements Component {
                    public Html render() { return (<p>{this.name}</p>); }
                }
                """);
        Files.writeString(source.resolve("ServerFixture.accent"), """
                package httpfixture;
                import accent.http.App;
                import accent.http.Response;
                import accent.http.Request;
                import java.util.Map;
                public class ServerFixture {
                    public static App create() {
                        final app = new App();
                        app.use((req, next) -> next.handle(req).header("X-Accent", "standalone"));
                        app.get("/users/:id", (req) -> Response.json(new User(req.path("id"), req.query("name"))));
                        app.get("/users/new", req -> Response.text("literal"));
                        app.post("/echo", req -> Response.text(req.body()));
                        app.post("/records", (Request req) -> {
                            final User user = req.json(User.class);
                            return Response.json(user).status(201);
                        });
                        app.post("/json", req -> Response.json(req.json()));
                        app.get("/inspect/:id", req -> Response.json(Map.of(
                            "id", req.path("id"), "query", req.queryValues("q"), "header", req.header("X-Test"))));
                        app.get("/html/:name", req -> Response.html(new Card(req.path("name"))));
                        app.get("/thread", req -> Response.json(Thread.currentThread().isVirtual()));
                        app.get("/async", req -> {
                            final valueAsync = async "async works";
                            return Response.text(await valueAsync);
                        });
                        app.get("/empty", req -> null);
                        app.get("/failure", req -> { throw new IllegalStateException("do-not-leak"); });
                        return app;
                    }
                }
                """);
        List<String> classpath = List.of(System.getProperty("accent.test.runtimeClasspath").split(java.io.File.pathSeparator));
        Path output = directory.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source), output, classpath, directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()});
        fixture = loader.loadClass("httpfixture.ServerFixture");
    }

    @AfterAll
    static void closeResources() throws Exception {
        if (loader != null) loader.close();
        if (client != null) client.close();
    }

    private static App fixtureApp() throws Exception { return (App) fixture.getMethod("create").invoke(null); }
    private static HttpRequest.Builder request(App app, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).timeout(Duration.ofSeconds(5));
    }
    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void accentHandlersReturnRecordsAndRunOnVirtualThreadsWithoutSpring() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            var response = send(request(app, "/users/42?name=Alice"));
            assertEquals(200, response.statusCode());
            assertEquals(Map.of("id", "42", "name", "Alice"), Json.parse(response.body()));
            assertEquals("application/json;charset=UTF-8", response.headers().firstValue("content-type").orElseThrow());
            assertEquals("standalone", response.headers().firstValue("X-Accent").orElseThrow());
            assertEquals("true", send(request(app, "/thread")).body());
            assertEquals("async works", send(request(app, "/async")).body());
        }
    }

    @Test
    void decodesPathSegmentsQueryDuplicatesUnicodeAndCaseInsensitiveHeaders() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            var response = send(request(app, "/inspect/A%2FB%2BC?q=a+b&q=%E4%BD%A0&q=%252F").header("x-test", "header"));
            assertEquals(200, response.statusCode());
            assertEquals(Map.of("id", "A/B+C", "query", List.of("a b", "你", "%2F"), "header", "header"), Json.parse(response.body()));
        }
    }

    @Test
    void literalRoutesTakePrecedenceAndMatchingUsesWholeSegments() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            assertEquals("literal", send(request(app, "/users/new")).body());
            assertEquals("literal", send(request(app, "/users/new/")).body());
            assertEquals(404, send(request(app, "/users/42/extra")).statusCode());
            assertEquals(404, send(request(app, "/user")).statusCode());
            assertEquals(404, send(request(app, "/unknown")).statusCode());
        }
    }

    @Test
    void returns405WithAllowAndAutomaticallyHandlesHeadAndOptions() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            var response = send(request(app, "/users/42").POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, response.statusCode());
            assertEquals("GET, HEAD, OPTIONS", response.headers().firstValue("Allow").orElseThrow());
            var head = send(request(app, "/users/new").method("HEAD", HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            assertEquals("7", head.headers().firstValue("Content-Length").orElseThrow());
            var options = send(request(app, "/users/42").method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
            assertEquals(204, options.statusCode());
            assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void acceptsTextAndTypedJsonBodiesAndRejectsMalformedJson() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            assertEquals("你好 José", send(request(app, "/echo").POST(HttpRequest.BodyPublishers.ofString("你好 José"))).body());
            var created = send(request(app, "/records").POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"7\",\"name\":\"Ana\"}")));
            assertEquals(201, created.statusCode());
            assertEquals(Map.of("id", "7", "name", "Ana"), Json.parse(created.body()));
            var invalid = send(request(app, "/json").POST(HttpRequest.BodyPublishers.ofString("{broken")));
            assertEquals(400, invalid.statusCode());
            assertEquals("Invalid JSON request body", invalid.body());
        }
    }

    @Test
    void serializesListsMapsScalarsAndNullWithoutSpringMessageConverters() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            for (String json : List.of("[1,2,3]", "{\"nested\":[true,null]}", "null", "42")) {
                var response = send(request(app, "/json").POST(HttpRequest.BodyPublishers.ofString(json)));
                assertEquals(200, response.statusCode());
                assertEquals(Json.parse(json), Json.parse(response.body()));
            }
        }
    }

    @Test
    void rendersAccentComponentsWithEscapingAndUnicode() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            var response = send(request(app, "/html/%3CJos%C3%A9%3E"));
            assertEquals(200, response.statusCode());
            assertEquals("text/html;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("<p>&lt;José&gt;</p>", response.body());
        }
    }

    @Test
    void nullResponseMeans204AndFailuresDoNotLeakExceptionDetails() throws Exception {
        try (App app = fixtureApp().listen("127.0.0.1", 0)) {
            var empty = send(request(app, "/empty"));
            assertEquals(204, empty.statusCode());
            assertEquals("", empty.body());
            var failure = send(request(app, "/failure"));
            assertEquals(500, failure.statusCode());
            assertEquals("Internal Server Error", failure.body());
            assertFalse(failure.body().contains("do-not-leak"));
        }
    }

    @Test
    void middlewareRunsInRegistrationOrderAndCanShortCircuit() throws Exception {
        var order = new ConcurrentLinkedQueue<String>();
        var calls = new AtomicInteger();
        try (App app = new App()) {
            app.use((req, next) -> { order.add("first-before"); Response result = next.handle(req); order.add("first-after"); return result.header("X-First", "yes"); });
            app.use((req, next) -> { order.add("second-before"); Response result = next.handle(req); order.add("second-after"); return result; });
            app.get("/", req -> { calls.incrementAndGet(); order.add("handler"); return Response.text("ok"); });
            app.listen("127.0.0.1", 0);
            assertEquals("yes", send(request(app, "/")).headers().firstValue("X-First").orElseThrow());
            assertEquals(List.of("first-before", "second-before", "handler", "second-after", "first-after"), List.copyOf(order));
            assertEquals(1, calls.get());
        }
        try (App app = new App()) {
            app.use((req, next) -> Response.text("unauthorized").status(401));
            app.get("/", req -> { calls.incrementAndGet(); return Response.text("should not run"); });
            app.listen("127.0.0.1", 0);
            assertEquals(401, send(request(app, "/")).statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void boundsKnownLengthAndChunkedBodies() throws Exception {
        try (App app = new App().maxBodyBytes(4)) {
            app.post("/", req -> Response.text(req.body()));
            app.listen("127.0.0.1", 0);
            assertEquals(200, send(request(app, "/").POST(HttpRequest.BodyPublishers.ofString("1234"))).statusCode());
            assertEquals(413, send(request(app, "/").POST(HttpRequest.BodyPublishers.ofString("12345"))).statusCode());
            assertEquals(413, send(request(app, "/").POST(HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))))).statusCode());
        }
    }

    @Test
    void concurrentRequestsProgressIndependentlyAndKeepRequestDataSeparate() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (App app = new App()) {
            app.get("/:id", req -> {
                assertTrue(Thread.currentThread().isVirtual());
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Response.text(req.path("id"));
            });
            app.listen("127.0.0.1", 0);
            var first = client.sendAsync(request(app, "/first").build(), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(app, "/second").build(), HttpResponse.BodyHandlers.ofString());
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals("first", first.get(5, TimeUnit.SECONDS).body());
            assertEquals("second", second.get(5, TimeUnit.SECONDS).body());
        } finally { release.countDown(); }
    }

    @Test
    void freezesConfigurationAndAllowsIdempotentClose() {
        App app = new App();
        assertThrows(IllegalStateException.class, app::port);
        app.get("/", req -> Response.text("ok"));
        app.listen("127.0.0.1", 0);
        assertThrows(IllegalStateException.class, () -> app.get("/other", req -> Response.text("late")));
        assertThrows(IllegalStateException.class, () -> app.use((req, next) -> next.handle(req)));
        assertThrows(IllegalStateException.class, () -> app.listen(0));
        app.close();
        app.close();
        assertThrows(IllegalStateException.class, app::port);
        assertThrows(IllegalStateException.class, () -> app.listen(0));
    }

    @Test
    void rejectsAmbiguousRoutesInvalidConfigurationAndHeaderInjection() {
        try (App app = new App()) {
            app.get("/users/:id", req -> Response.text("ok"));
            assertThrows(IllegalArgumentException.class, () -> app.get("/users/:name", req -> Response.text("duplicate")));
            assertThrows(IllegalArgumentException.class, () -> app.get("/:id/:id", req -> Response.text("duplicate parameter")));
            assertThrows(IllegalArgumentException.class, () -> app.get("relative", req -> Response.text("invalid")));
            assertThrows(IllegalArgumentException.class, () -> app.maxBodyBytes(0));
            assertThrows(IllegalArgumentException.class, () -> app.shutdownTimeout(Duration.ofSeconds(-1)));
            assertThrows(IllegalArgumentException.class, () -> Response.text("ok").header("X-Test", "a\r\nb"));
            assertThrows(IllegalArgumentException.class, () -> Response.text("ok").header("Content-Length", "123"));
            assertThrows(IllegalArgumentException.class, () -> Response.empty(100));
        }
    }

    @Test
    void gracefulCloseLetsAnInflightRequestFinishAndReleasesThePort() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        App app = new App();
        app.get("/", req -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return Response.text("finished"); });
        app.listen("127.0.0.1", 0);
        int port = app.port();
        var response = client.sendAsync(request(app, "/").build(), HttpResponse.BodyHandlers.ofString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> closing = executor.submit(app::close);
            release.countDown();
            assertEquals("finished", response.get(5, TimeUnit.SECONDS).body());
            closing.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); app.close(); }
        try (App replacement = new App()) {
            replacement.get("/", req -> Response.text("reused"));
            replacement.listen("127.0.0.1", port);
            assertEquals("reused", send(request(replacement, "/")).body());
        }
    }

    @Test
    void cliCarriesTheHttpRuntimeIntoItsChildJvmWithoutAnExplicitClasspath() throws Exception {
        Path cliSource = Files.createDirectories(directory.resolve("cli-source"));
        // A project-local JDK must not override the JVM that compiled the program.
        Path fakeJava = Files.createDirectories(cliSource.resolve("jdk/bin")).resolve("java");
        Files.writeString(fakeJava, "#!/bin/sh\nexit 73\n");
        fakeJava.toFile().setExecutable(true);
        Path source = cliSource.resolve("CliHttp.accent");
        Files.writeString(source, """
                import accent.http.App;
                import accent.http.Response;
                public class CliHttp {
                    public static void main(String[] args) {
                        final app = new App();
                        app.get("/", (req) -> Response.text("cli"));
                        app.listen("127.0.0.1", 0);
                        System.out.println("HTTP_PORT=" + app.port());
                        app.close();
                    }
                }
                """);
        Path output = directory.resolve("cli-output.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("accent.test.runtimeClasspath"),
                    "accent.cli.AccentCli", "run", source.toString(), "-d", cliSource.resolve("build/classes").toString(),
                    "--save-java", cliSource.resolve("build/generated").toString()).directory(cliSource.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), () -> read(output));
                assertTrue(read(output).contains("HTTP_PORT="), () -> read(output));
            } finally { process.destroyForcibly(); }
        }
    }

    @Test
    void supportsAllRouteMethodsAndExplicitHeadAndOptionsHandlers() throws Exception {
        try (App app = new App()) {
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                app.route(method, "/verbs", req -> Response.text(req.method()).status(201));
            }
            app.head("/verbs", req -> Response.empty(202).header("X-Head", "explicit"));
            app.options("/verbs", req -> Response.text("custom options").status(200));
            app.listen("127.0.0.1", 0);
            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                var response = send(request(app, "/verbs").method(method, HttpRequest.BodyPublishers.noBody()));
                assertEquals(201, response.statusCode());
                assertEquals(method, response.body());
            }
            var head = send(request(app, "/verbs").method("HEAD", HttpRequest.BodyPublishers.noBody()));
            assertEquals(202, head.statusCode());
            assertEquals("explicit", head.headers().firstValue("X-Head").orElseThrow());
            assertEquals("custom options", send(request(app, "/verbs").method("OPTIONS", HttpRequest.BodyPublishers.noBody())).body());
        }
    }

    @Test
    void intentionalErrorsAndBodylessStatusesPreserveHttpSemantics() throws Exception {
        try (App app = new App()) {
            app.get("/error", req -> { throw new HttpException(422, "invalid input"); });
            app.get("/bodyless", req -> Response.text("must be omitted").status(204));
            app.get("/binary", req -> Response.bytes(new byte[]{0, 1, 2}, "application/octet-stream"));
            app.listen("127.0.0.1", 0);
            var error = send(request(app, "/error"));
            assertEquals(422, error.statusCode());
            assertEquals("invalid input", error.body());
            assertEquals("", send(request(app, "/bodyless")).body());
            var binary = client.send(request(app, "/binary").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(new byte[]{0, 1, 2}, binary.body());
        }
    }

    @Test
    @Timeout(40)
    void readmeExampleRunsThroughCliAndStopsItsServerChildOnTermination() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String section = readme.split("## Standalone HTTP — Undertow by Default", 2)[1];
        String example = section.split("```accent\\n", 2)[1].split("```", 2)[0];
        Path sources = Files.createDirectories(directory.resolve("readme-source"));
        Path source = sources.resolve("Main.accent");
        Files.writeString(source, example);
        Path log = directory.resolve("readme-http.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("accent.test.runtimeClasspath"),
                "accent.cli.AccentCli", "run", source.toString(), "-d", directory.resolve("readme-classes").toString(),
                "--save-java", directory.resolve("readme-generated").toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PORT", "0");
        Process process = builder.start();
        List<ProcessHandle> children = List.of();
        try {
            int port = awaitPort(process, log);
            children = process.descendants().toList();
            URI base = URI.create("http://127.0.0.1:" + port);
            var health = client.send(HttpRequest.newBuilder(base.resolve("/health")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("ok", health.body());
            var user = client.send(HttpRequest.newBuilder(base.resolve("/users/42")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(Map.of("id", "42"), Json.parse(user.body()));
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            for (ProcessHandle child : children) assertFalse(child.isAlive(), "CLI must terminate its server JVM");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            for (ProcessHandle child : children) if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static int awaitPort(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var pattern = java.util.regex.Pattern.compile("Accent HTTP listening on (\\d+)");
        while (System.nanoTime() < deadline) {
            var matcher = pattern.matcher(read(log));
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
            if (!process.isAlive()) fail(read(log));
            Thread.sleep(20);
        }
        throw new AssertionError("Server did not start: " + read(log));
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (java.io.IOException exception) { throw new AssertionError(exception); }
    }
}
