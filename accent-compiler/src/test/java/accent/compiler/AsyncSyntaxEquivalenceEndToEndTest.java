package accent.compiler;

import accent.runtime.Async;
import accent.runtime.AccentExecutionException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the same behavioral contract against shorthand and explicit method syntax. */
@Timeout(15)
class AsyncSyntaxEquivalenceEndToEndTest {
    @TempDir static Path directory;
    private static URLClassLoader loader;

    private static String launch(String expression, boolean shorthand) {
        return shorthand ? "async (" + expression + ")" : "Async.run(() -> " + expression + ")";
    }

    private static String waitFor(String expression, boolean shorthand) {
        return shorthand ? "(await (" + expression + "))" : "(" + expression + ").await()";
    }

    @BeforeAll
    static void compileBothForms() throws Exception {
        Path sources = Files.createDirectories(directory.resolve("sources"));
        for (boolean shorthand : List.of(false, true)) {
            String name = shorthand ? "Shorthand" : "Methods";
            String code = """
                    package equivalence;
                    import accent.runtime.Async;
                    import accent.compiler.AsyncConcurrencyProbe;
                    import java.util.List;
                    import java.util.ArrayList;
                    public class $NAME {
                        public static Async<String> launch(AsyncConcurrencyProbe probe) { return $LAUNCH; }
                        public static String finish(Async<String> valueAsync) { return $WAIT; }
                        public static Async<Void> launchVoid(AsyncConcurrencyProbe probe) { return $VOID; }
                        public static Async<String> capture(String prefix, AsyncConcurrencyProbe probe) {
                            final suffix = "!";
                            return $CAPTURE;
                        }
                        public static Async<String> shared(AsyncConcurrencyProbe probe) {
                            final values = new ArrayList<String>();
                            final valueAsync = $SHARED;
                            values.add("later");
                            return valueAsync;
                        }
                        public static String factory(AsyncConcurrencyProbe probe) { return $FACTORY; }
                        public static int arithmetic() { return $ARITHMETIC; }
                        public static String sequence(AsyncConcurrencyProbe first, AsyncConcurrencyProbe second) { return $SEQUENCE; }
                        public static boolean skipped(AsyncConcurrencyProbe probe) {
                            final left = false and ($SKIPPED.length() > 0);
                            final right = true or ($SKIPPED.length() > 0);
                            return not left and right;
                        }
                        public static boolean nested(Async<Async<Boolean>> valueAsync) { return $NESTED; }
                        public static Async<List<String>> list(List<String> values) { return $LIST; }
                        public static List<String> finishList(Async<List<String>> valueAsync) { return $WAIT; }
                    }
                    """
                    .replace("$NAME", name)
                    .replace("$LAUNCH", launch("probe.run()", shorthand))
                    .replace("$VOID", launch("probe.runVoid()", shorthand))
                    .replace("$CAPTURE", launch("prefix + probe.run() + suffix", shorthand))
                    .replace("$SHARED", launch("probe.run() + values.get(0)", shorthand))
                    .replace("$FACTORY", waitFor(launch("probe.run()", shorthand), shorthand))
                    .replace("$ARITHMETIC", waitFor(launch("20 + 1", shorthand), shorthand) + " * 2")
                    .replace("$SEQUENCE", waitFor(launch("first.run() + second.run()", shorthand), shorthand))
                    .replace("$SKIPPED", waitFor(launch("probe.run()", shorthand), shorthand))
                    .replace("$NESTED", waitFor(waitFor("valueAsync", shorthand), shorthand))
                    .replace("$LIST", launch("values", shorthand))
                    .replace("$WAIT", waitFor("valueAsync", shorthand));
            Files.writeString(sources.resolve(name + ".accent"), code);
        }
        var result = compile(sources, directory.resolve("classes"));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()});
    }

    private static CompilationResult compile(Path source, Path output) {
        return new AccentCompiler().compile(List.of(source), output,
                List.of("build/classes/java/main", "build/classes/java/test"), output.resolveSibling("generated"), true);
    }

    @AfterAll
    static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    private static Object invoke(String form, String method, Class<?>[] types, Object... arguments) throws Throwable {
        try { return loader.loadClass("equivalence." + form).getMethod(method, types).invoke(null, arguments); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    private static Async<?> launch(String form, AsyncConcurrencyProbe probe) throws Throwable {
        return (Async<?>) invoke(form, "launch", new Class<?>[]{AsyncConcurrencyProbe.class}, probe);
    }

    private static Object finish(String form, Async<?> valueAsync) throws Throwable {
        return invoke(form, "finish", new Class<?>[]{Async.class}, valueAsync);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void defersEvaluationAndCapturesParametersAndLocals(String form) throws Throwable {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var probe = new AsyncConcurrencyProbe(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "value";
        });
        Async<?> valueAsync = (Async<?>) invoke(form, "capture",
                new Class<?>[]{String.class, AsyncConcurrencyProbe.class}, "你好-", probe);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(valueAsync.isDone(), "Starting work must return before its operation completes");
        } finally { release.countDown(); }
        assertEquals("你好-value!", finish(form, valueAsync));
        assertEquals("你好-value!", finish(form, valueAsync));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void capturedReferencesRemainShared(String form) throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        var probe = new AsyncConcurrencyProbe(() -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "seen-";
        });
        Async<?> valueAsync = (Async<?>) invoke(form, "shared", new Class<?>[]{AsyncConcurrencyProbe.class}, probe);
        release.countDown();
        assertEquals("seen-later", finish(form, valueAsync));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void evaluatesFutureFactoriesOnceAndPreservesPrecedence(String form) throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        var probe = new AsyncConcurrencyProbe(() -> "value-" + calls.incrementAndGet());
        assertEquals("value-1", invoke(form, "factory", new Class<?>[]{AsyncConcurrencyProbe.class}, probe));
        assertEquals(1, calls.get());
        assertEquals(42, invoke(form, "arithmetic", new Class<?>[]{}));
        Async<Async<Boolean>> nestedAsync = Async.run(() -> Async.run(() -> true));
        assertEquals(true, invoke(form, "nested", new Class<?>[]{Async.class}, nestedAsync));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void shortCircuitingDoesNotStartSkippedWork(String form) throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        var probe = new AsyncConcurrencyProbe(() -> { calls.incrementAndGet(); return "unexpected"; });
        assertEquals(true, invoke(form, "skipped", new Class<?>[]{AsyncConcurrencyProbe.class}, probe));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void evaluatesCompoundOperationsInOrderOnTheSameVirtualThread(String form) throws Throwable {
        var order = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var threads = new java.util.concurrent.ConcurrentLinkedQueue<Thread>();
        var first = new AsyncConcurrencyProbe(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            threads.add(Thread.currentThread());
            order.add("first");
            return "A";
        });
        var second = new AsyncConcurrencyProbe(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            threads.add(Thread.currentThread());
            order.add("second");
            return "B";
        });
        assertEquals("AB", invoke(form, "sequence",
                new Class<?>[]{AsyncConcurrencyProbe.class, AsyncConcurrencyProbe.class}, first, second));
        assertEquals(List.of("first", "second"), List.copyOf(order));
        var executed = List.copyOf(threads);
        assertSame(executed.get(0), executed.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void preservesGenericCollectionIdentityAndNullResults(String form) throws Throwable {
        List<String> values = new ArrayList<>(List.of("Alice", "Bob"));
        Async<?> listAsync = (Async<?>) invoke(form, "list", new Class<?>[]{List.class}, values);
        assertSame(values, invoke(form, "finishList", new Class<?>[]{Async.class}, listAsync));
        Async<?> nullAsync = launch(form, new AsyncConcurrencyProbe(() -> null));
        assertNull(finish(form, nullAsync));
        assertThrows(NullPointerException.class, () -> finish(form, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void rethrowsRuntimeFailuresAndErrorsWithoutWrapping(String form) throws Throwable {
        IllegalArgumentException failure = new IllegalArgumentException("failure");
        Async<?> failedAsync = launch(form, new AsyncConcurrencyProbe(() -> { throw failure; }));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> finish(form, failedAsync)));
        AssertionError error = new AssertionError("error");
        Async<?> errorAsync = launch(form, new AsyncConcurrencyProbe(() -> { throw error; }));
        assertSame(error, assertThrows(AssertionError.class, () -> finish(form, errorAsync)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void wrapsCheckedFailuresFromVoidOperations(String form) throws Throwable {
        IOException failure = new IOException("void failure");
        Async<?> failedAsync = (Async<?>) invoke(form, "launchVoid", new Class<?>[]{AsyncConcurrencyProbe.class},
                new AsyncConcurrencyProbe(() -> { throw failure; }));
        assertSame(failure, assertThrows(AccentExecutionException.class, failedAsync::await).getCause());
        assertSame(failure, assertThrows(AccentExecutionException.class, failedAsync::await).getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void interruptingOneAwaiterDoesNotCancelWork(String form) throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        Async<?> valueAsync = launch(form, new AsyncConcurrencyProbe(() -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "completed";
        }));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> finish(form, valueAsync));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(valueAsync.isCancelled());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        assertEquals("completed", finish(form, valueAsync));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shorthand", "Methods"})
    void rejectsReassignedCapturedLocals(String form) throws Exception {
        boolean shorthand = form.equals("Shorthand");
        Path sources = Files.createTempDirectory(directory, "capture-");
        Files.writeString(sources.resolve("Invalid.accent"), """
                import accent.runtime.Async;
                public class Invalid {
                    public static String run() {
                        var value = "before";
                        final valueAsync = %s;
                        value = "after";
                        return %s;
                    }
                }
                """.formatted(launch("value", shorthand), waitFor("valueAsync", shorthand)));
        assertFalse(compile(sources, sources.resolve("classes")).successful());
    }
}
