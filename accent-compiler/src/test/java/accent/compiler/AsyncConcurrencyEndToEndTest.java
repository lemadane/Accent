package accent.compiler;

import accent.runtime.Async;
import accent.runtime.AccentExecutionException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Executes compiled Accent expressions, including await calls, against real virtual threads. */
@Timeout(15)
class AsyncConcurrencyEndToEndTest {
    @TempDir static Path directory;
    private static URLClassLoader loader;
    private static Class<?> program;

    @BeforeAll
    static void compileProgram() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("Program.accent"), """
                package asynctest;
                import accent.runtime.Async;
                import accent.compiler.AsyncConcurrencyProbe;
                import java.time.Duration;
                import accent.html.Html;
                class Program {
                    static Async<String> start(AsyncConcurrencyProbe probe) { return Async.run(() -> probe.run()); }
                    static Async<Void> startVoid(AsyncConcurrencyProbe probe) { return Async.run(() -> probe.runVoid()); }
                    static Async<String> nested(AsyncConcurrencyProbe probe) { return Async.run(() -> start(probe).await()); }
                    static Object finishVoid(Async<Void> valueAsync) { return valueAsync.await(); }
                    static String finish(Async<String> valueAsync) { return valueAsync.await(); }
                    static String timed(Async<String> valueAsync, Duration timeout) { return valueAsync.await(timeout); }
                    static boolean cancel(Async<String> valueAsync) { return valueAsync.cancel(); }
                    static boolean done(Async<String> valueAsync) { return valueAsync.isDone(); }
                    static boolean cancelled(Async<String> valueAsync) { return valueAsync.isCancelled(); }
                    static int stringLength(AsyncConcurrencyProbe probe) {
                        final valueAsync = Async.run(() -> probe.run());
                        final value = valueAsync.await();
                        return value.length();
                    }
                    static int inferred() {
                        final numberAsync = Async.run(() -> 42);
                        final number = numberAsync.await();
                        return number;
                    }
                    static String typed(AsyncConcurrencyProbe probe) {
                        final Async<String> valueAsync = Async.run(() -> probe.run());
                        return valueAsync.await();
                    }
                    static String chained(AsyncConcurrencyProbe probe) { return Async.run(() -> probe.run()).await(); }
                    static String block(AsyncConcurrencyProbe probe) {
                        final valueAsync = Async.run(() -> {
                            final value = probe.run();
                            return value.trim();
                        });
                        return valueAsync.await();
                    }
                    static int conditional() {
                        final valueAsync = Async.run(() -> if (true) { yield 21; } else { yield 0; });
                        return valueAsync.await() * 2;
                    }
                    static Html markup() {
                        final valueAsync = Async.run(() -> (<p>Async HTML</p>));
                        return valueAsync.await();
                    }
                    static Async<String> startShort(AsyncConcurrencyProbe probe) { return async probe.run(); }
                    static Async<Void> voidShort(AsyncConcurrencyProbe probe) { return async probe.runVoid(); }
                    static String finishShort(Async<String> valueAsync) { return await valueAsync; }
                    static Object voidAwaitShort(Async<Void> valueAsync) { return await valueAsync; }
                    static int immediateShort() { return await async 42; }
                    static int inferShort() {
                        final valueAsync = async "hello";
                        final value = await valueAsync;
                        return value.length();
                    }
                    static int precedence() {
                        final valueAsync = async (20 + 1);
                        return (await valueAsync) * 2;
                    }
                    static Html markupShort() {
                        final valueAsync = async (<p>Short HTML</p>);
                        return await valueAsync;
                    }
                    static String nestedShort(AsyncConcurrencyProbe probe) {
                        final outerAsync = async (async probe.run());
                        return await (await outerAsync);
                    }
                }
                """);
        var result = compile(source, directory.resolve("classes"));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()});
        program = loader.loadClass("asynctest.Program");
    }

    private static CompilationResult compile(Path source, Path output) {
        return new AccentCompiler().compile(List.of(source), output,
                List.of("build/classes/java/main", "build/classes/java/test"), output.resolveSibling("generated"), true);
    }

    @AfterAll
    static void close() throws Exception { if (loader != null) loader.close(); }

    private static Object invoke(String method, Class<?>[] parameters, Object... arguments) throws Throwable {
        try { return program.getMethod(method, parameters).invoke(null, arguments); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    private static Async<?> start(Callable<String> operation) throws Throwable {
        return (Async<?>) invoke("start", new Class<?>[]{AsyncConcurrencyProbe.class}, new AsyncConcurrencyProbe(operation));
    }

    private static String finish(Async<?> valueAsync) throws Throwable {
        return (String) invoke("finish", new Class<?>[]{Async.class}, valueAsync);
    }

    @Test
    void infersAndPreservesTypesAcrossMethodBoundaries() throws Throwable {
        assertEquals(42, invoke("inferred", new Class<?>[]{}));
        assertEquals(5, invoke("stringLength", new Class<?>[]{AsyncConcurrencyProbe.class}, new AsyncConcurrencyProbe(() -> "hello")));
        assertEquals("typed", invoke("typed", new Class<?>[]{AsyncConcurrencyProbe.class}, new AsyncConcurrencyProbe(() -> "typed")));
        assertEquals("chained", invoke("chained", new Class<?>[]{AsyncConcurrencyProbe.class}, new AsyncConcurrencyProbe(() -> "chained")));
        assertNull(finish(start(() -> null)));
    }

    @Test
    void startsIndependentOperationsBeforeAwaitingOnVirtualThreads() throws Throwable {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<String> operation = () -> {
            assertTrue(Thread.currentThread().isVirtual());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Thread.currentThread().getName();
        };
        Async<?> first = start(operation);
        Async<?> second = start(operation);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "Both tasks must start before either is awaited");
            assertEquals(false, invoke("done", new Class<?>[]{Async.class}, first));
        } finally { release.countDown(); }
        assertNotEquals(finish(first), finish(second));
        assertEquals(true, invoke("done", new Class<?>[]{Async.class}, first));
        assertEquals(false, invoke("cancelled", new Class<?>[]{Async.class}, first));
    }

    @Test
    void repeatedAwaitReturnsTheSameResultWithoutRepeatingWork() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        Async<?> valueAsync = start(() -> "result-" + calls.incrementAndGet());
        assertEquals("result-1", finish(valueAsync));
        assertEquals("result-1", finish(valueAsync));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesRuntimeAndCheckedFailuresAndErrors() throws Throwable {
        IllegalStateException runtime = new IllegalStateException("runtime");
        Async<?> runtimeAsync = start(() -> { throw runtime; });
        assertSame(runtime, assertThrows(IllegalStateException.class, () -> finish(runtimeAsync)));
        assertSame(runtime, assertThrows(IllegalStateException.class, () -> finish(runtimeAsync)));
        IOException checked = new IOException("checked");
        Async<?> checkedAsync = start(() -> { throw checked; });
        assertSame(checked, assertThrows(AccentExecutionException.class, () -> finish(checkedAsync)).getCause());
        AssertionError error = new AssertionError("error");
        Async<?> errorAsync = start(() -> { throw error; });
        assertSame(error, assertThrows(AssertionError.class, () -> finish(errorAsync)));
    }

    @Test
    void timeoutLeavesWorkRunningAndAllowsLaterAwait() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        Async<?> valueAsync = start(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return "later"; });
        try {
            var exception = assertThrows(AccentExecutionException.class, () -> invoke("timed",
                    new Class<?>[]{Async.class, Duration.class}, valueAsync, Duration.ofMillis(20)));
            assertInstanceOf(TimeoutException.class, exception.getCause());
            assertEquals(false, invoke("cancelled", new Class<?>[]{Async.class}, valueAsync));
            assertEquals(false, invoke("done", new Class<?>[]{Async.class}, valueAsync));
        } finally { release.countDown(); }
        assertEquals("later", finish(valueAsync));
        assertEquals("later", invoke("timed", new Class<?>[]{Async.class, Duration.class}, valueAsync, Duration.ofSeconds(1)));
    }

    @Test
    void cancellationInterruptsRunningWorkAndPreventsAwait() throws Throwable {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Async<?> valueAsync = start(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { interrupted.countDown(); throw exception; }
            return "unexpected";
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(true, invoke("cancel", new Class<?>[]{Async.class}, valueAsync));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(true, invoke("done", new Class<?>[]{Async.class}, valueAsync));
            assertEquals(true, invoke("cancelled", new Class<?>[]{Async.class}, valueAsync));
            assertThrows(CancellationException.class, () -> finish(valueAsync));
            assertEquals(false, invoke("cancel", new Class<?>[]{Async.class}, valueAsync));
        } finally { valueAsync.cancel(); }
    }

    @Test
    void failureDoesNotCancelIndependentSiblingWork() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        Async<?> sibling = start(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return "survived"; });
        try {
            Async<?> failure = start(() -> { throw new IllegalArgumentException("failed"); });
            assertThrows(IllegalArgumentException.class, () -> finish(failure));
            assertFalse(sibling.isCancelled());
        } finally { release.countDown(); }
        assertEquals("survived", finish(sibling));
    }

    @Test
    void supportsNestedAsyncAndVoidOperations() throws Throwable {
        Async<?> nested = (Async<?>) invoke("nested", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> "nested"));
        assertEquals("nested", finish(nested));
        AtomicInteger calls = new AtomicInteger();
        Async<?> voidAsync = (Async<?>) invoke("startVoid", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> { calls.incrementAndGet(); return "ignored"; }));
        assertNull(invoke("finishVoid", new Class<?>[]{Async.class}, voidAsync));
        assertEquals(1, calls.get());
        IOException failure = new IOException("void failure");
        Async<?> failedAsync = (Async<?>) invoke("startVoid", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> { throw failure; }));
        assertSame(failure, assertThrows(AccentExecutionException.class,
                () -> invoke("finishVoid", new Class<?>[]{Async.class}, failedAsync)).getCause());
    }

    @Test
    void multipleCallersCanAwaitTheSameOperation() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        Async<?> valueAsync = start(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return "shared"; });
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    try { return finish(valueAsync); }
                    catch (Throwable exception) { throw new AssertionError(exception); }
                }));
            }
            release.countDown();
            for (Future<String> result : results) assertEquals("shared", result.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); valueAsync.cancel(); }
    }

    @Test
    void interruptingAnAwaiterPreservesInterruptStatusWithoutCancellingWork() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        Async<?> valueAsync = start(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return "completed"; });
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> finish(valueAsync));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(valueAsync.isCancelled());
        } finally { Thread.interrupted(); release.countDown(); }
        assertEquals("completed", finish(valueAsync));
    }

    @Test
    void completedWorkCannotBeCancelledAndTimeoutRequiresDuration() throws Throwable {
        Async<?> valueAsync = start(() -> "done");
        assertEquals("done", finish(valueAsync));
        assertEquals(false, invoke("cancel", new Class<?>[]{Async.class}, valueAsync));
        assertEquals(false, invoke("cancelled", new Class<?>[]{Async.class}, valueAsync));
        assertThrows(NullPointerException.class, () -> invoke("timed",
                new Class<?>[]{Async.class, Duration.class}, valueAsync, null));
    }

    @Test
    void lambdaBlocksAndAccentExpressionsExecuteInsideTheTask() throws Throwable {
        assertEquals("block", invoke("block", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> " block ")));
        assertEquals(42, invoke("conditional", new Class<?>[]{}));
        var html = (accent.html.Html) invoke("markup", new Class<?>[]{});
        var text = new java.io.StringWriter();
        html.writeTo(new accent.html.HtmlWriter(text));
        assertEquals("<p>Async HTML</p>", text.toString());
    }

    @Test
    void shorthandAndMethodFormsAreInterchangeableAndRunConcurrently() throws Throwable {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AsyncConcurrencyProbe probe = new AsyncConcurrencyProbe(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "result";
        });
        Async<?> methodAsync = (Async<?>) invoke("start", new Class<?>[]{AsyncConcurrencyProbe.class}, probe);
        Async<?> shortAsync = (Async<?>) invoke("startShort", new Class<?>[]{AsyncConcurrencyProbe.class}, probe);
        try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        assertEquals("result", finish(shortAsync));
        assertEquals("result", invoke("finishShort", new Class<?>[]{Async.class}, methodAsync));
        assertEquals(5, invoke("inferShort", new Class<?>[]{}));
        assertEquals(42, invoke("precedence", new Class<?>[]{}));
        assertEquals(42, invoke("immediateShort", new Class<?>[]{}));
        assertEquals("result", invoke("nestedShort", new Class<?>[]{AsyncConcurrencyProbe.class}, probe));
        var html = (accent.html.Html) invoke("markupShort", new Class<?>[]{});
        var text = new java.io.StringWriter();
        html.writeTo(new accent.html.HtmlWriter(text));
        assertEquals("<p>Short HTML</p>", text.toString());
        String generated = Files.readString(directory.resolve("generated/asynctest/Program.java"));
        assertTrue(generated.contains("accent.runtime.Async.run("));
        assertFalse(generated.contains("async probe.run()"));
    }

    @Test
    void shorthandPropagatesCheckedFailuresVoidResultsAndCancellation() throws Throwable {
        IOException failure = new IOException("short failure");
        Async<?> valueAsync = (Async<?>) invoke("startShort", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> { throw failure; }));
        assertSame(failure, assertThrows(AccentExecutionException.class,
                () -> invoke("finishShort", new Class<?>[]{Async.class}, valueAsync)).getCause());
        Async<?> voidAsync = (Async<?>) invoke("voidShort", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> "ignored"));
        assertNull(invoke("voidAwaitShort", new Class<?>[]{Async.class}, voidAsync));
        CountDownLatch release = new CountDownLatch(1);
        Async<?> cancelledAsync = (Async<?>) invoke("startShort", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> { release.await(5, TimeUnit.SECONDS); return "cancelled"; }));
        try {
            assertTrue(cancelledAsync.cancel());
            assertThrows(CancellationException.class,
                    () -> invoke("finishShort", new Class<?>[]{Async.class}, cancelledAsync));
        } finally { release.countDown(); }
    }

    @Test
    void rejectsInvalidAsyncOperations() throws Exception {
        for (String expression : List.of("await 42", "42.await()", "valueAsync.await(\"invalid timeout\")", "Async.run(42)", "Async.run((int value) -> value)")) {
            Path source = Files.createTempDirectory(directory, "invalid-");
            Files.writeString(source.resolve("Invalid.accent"), """
                    import accent.runtime.Async;
                    class Invalid {
                        static void main(String[] args) {
                            final valueAsync = Async.run(() -> 42);
                            final result = %s;
                        }
                    }
                    """.formatted(expression));
            assertFalse(compile(source, source.resolve("classes")).successful(), expression);
        }
    }
}
