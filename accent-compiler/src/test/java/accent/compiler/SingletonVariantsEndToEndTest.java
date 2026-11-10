package accent.compiler;

import accent.json.Json;
import accent.json.JsonMappingException;
import accent.runtime.Singleton;
import accent.source.SourceFile;
import accent.xml.Xml;
import java.io.*;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SingletonVariantsEndToEndTest {
    @TempDir static Path tempDir;
    private static Path output;
    private static String runtime;
    private static URLClassLoader loader;

    @BeforeAll
    static void compileVariants() throws Exception {
        Path sources = Files.createDirectory(tempDir.resolve("src"));
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("Probe", """
                class Probe {
                    static int classCalls = 0;
                    static int recordCalls = 0;
                    static int modelCalls = 0;
                    static int classCreated() { Probe.classCalls++; return 1; }
                    static String recordName() { Probe.recordCalls++; return "light"; }
                    static String modelName() { Probe.modelCalls++; return "app"; }
                }
                """);
        fixtures.put("Config", """
                singleton class Config implements java.io.Serializable {
                    private int _count = 0;
                    private int _created = Probe.classCreated();
                    int count() { return this._count; }
                    void increment() { this._count++; }
                }
                """);
        fixtures.put("Defaults", """
                singleton record Defaults(String theme = Probe.recordName(), int retryLimit = 1 + 2) {}
                """);
        fixtures.put("State", """
                singleton model State(final String id = Probe.modelName(), String theme = "light", int count = 0) {}
                """);
        fixtures.put("Computed", "singleton record Computed(String name = Probe.recordName() ?: \"fallback\") {}");
        fixtures.put("EmptyClass", "singleton class EmptyClass {}");
        fixtures.put("EmptyRecord", "singleton record EmptyRecord() {}");
        fixtures.put("EmptyModel", "singleton model EmptyModel() {}");
        fixtures.put("Calls", """
                import java.util.*;
                class Calls {
                    static Config config() { return Config.instance(); }
                    static Defaults defaults() { return Defaults.instance(); }
                    static State state() { return State.instance(); }
                    static String defaultsJson() { return Defaults.instance().toJson(); }
                    static String defaultsXml() { return Defaults.instance().toXml(); }
                    static String stateList() {
                        List<State> values = List.of(State.instance());
                        return values.toJson();
                    }
                    static String defaultsMap() {
                        Map<String, Defaults> values? = Map.of("default", Defaults.instance());
                        return (values?).toXml();
                    }
                    static void main(String[] args) {
                        if (Config.instance() != Config.instance()) { throw new RuntimeException("class identity"); }
                        if (Defaults.instance() != Defaults.instance()) { throw new RuntimeException("record identity"); }
                        if (State.instance() != State.instance()) { throw new RuntimeException("model identity"); }
                        State.instance().theme("dark");
                        if (not State.instance().theme().equals("dark")) { throw new RuntimeException("shared state"); }
                        System.out.println(Defaults.instance().toJson());
                        System.out.println(State.instance().toXml());
                    }
                }
                """);
        for (var entry : fixtures.entrySet()) {
            Files.writeString(sources.resolve(entry.getKey() + ".accent"), "package singletons;\n" + entry.getValue());
        }
        runtime = Path.of(Singleton.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        output = tempDir.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output, List.of(runtime), tempDir.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        loader = freshLoader();
    }

    private static URLClassLoader freshLoader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()});
    }

    @AfterAll
    static void close() throws Exception { if (loader != null) loader.close(); }

    @TestFactory
    Stream<DynamicTest> eachVariantHasOneFinalInstanceAndPrivateConstruction() {
        return Stream.of("Config", "Defaults", "State", "Computed", "EmptyClass", "EmptyRecord", "EmptyModel")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    Class<?> type = loader.loadClass("singletons." + name);
                    Object first = type.getMethod("instance").invoke(null);
                    assertSame(first, type.getMethod("instance").invoke(null));
                    assertTrue(Modifier.isFinal(type.getModifiers()));
                    assertTrue(type.isAnnotationPresent(Singleton.class));
                    assertTrue(Arrays.stream(type.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())));
                    assertTrue(Arrays.stream(type.getMethods()).noneMatch(m -> m.getName().equals("copy")));
                    if (name.startsWith("Empty") && !name.equals("EmptyClass")) assertEquals(name + "[]", first.toString());
                }));
    }

    @TestFactory
    Stream<DynamicTest> concurrentFirstAccessIsLazyAndInitializesOnlyOnce() {
        return Stream.of("Config", "Defaults", "State").map(name -> DynamicTest.dynamicTest(name + " concurrent lazy initialization", () -> {
            try (var isolated = freshLoader()) {
                Class<?> probe = isolated.loadClass("singletons.Probe");
                String field = name.equals("Config") ? "classCalls" : name.equals("Defaults") ? "recordCalls" : "modelCalls";
                Class<?> type = Class.forName("singletons." + name, true, isolated);
                assertEquals(0, probe.getField(field).getInt(null));
                var instance = type.getMethod("instance");
                int workers = 16;
                CountDownLatch start = new CountDownLatch(1);
                try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
                    List<Future<Object>> results = new ArrayList<>();
                    for (int i = 0; i < workers; i++) results.add(pool.submit(() -> {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return instance.invoke(null);
                    }));
                    start.countDown();
                    Object expected = results.get(0).get(10, TimeUnit.SECONDS);
                    for (var result : results) assertSame(expected, result.get(10, TimeUnit.SECONDS));
                }
                assertEquals(1, probe.getField(field).getInt(null));
            }
        }));
    }

    @Test
    void classMethodsRecordImmutabilityAndModelMutabilityWorkFromAccent() throws Exception {
        Class<?> calls = loader.loadClass("singletons.Calls");
        Object config = calls.getMethod("config").invoke(null);
        config.getClass().getMethod("increment").invoke(config);
        assertEquals(1, config.getClass().getMethod("count").invoke(calls.getMethod("config").invoke(null)));
        Class<?> computed = loader.loadClass("singletons.Computed");
        assertEquals("light", computed.getMethod("name").invoke(computed.getMethod("instance").invoke(null)));
        Object defaults = calls.getMethod("defaults").invoke(null);
        assertFalse(defaults.getClass().isRecord(), "Singleton record must use a class to hide construction");
        assertEquals("light", defaults.getClass().getMethod("theme").invoke(defaults));
        assertEquals(3, defaults.getClass().getMethod("retryLimit").invoke(defaults));
        assertTrue(Modifier.isFinal(defaults.getClass().getDeclaredField("theme").getModifiers()));
        assertThrows(NoSuchMethodException.class, () -> defaults.getClass().getMethod("theme", String.class));
        Object state = calls.getMethod("state").invoke(null);
        assertEquals("app", state.getClass().getMethod("id").invoke(state));
        assertThrows(NoSuchMethodException.class, () -> state.getClass().getMethod("id", String.class));
        state.getClass().getMethod("theme", String.class).invoke(state, "dark");
        assertEquals("dark", state.getClass().getMethod("theme").invoke(calls.getMethod("state").invoke(null)));
        state.getClass().getMethod("theme", String.class).invoke(state, "light");
        assertEquals("{\"theme\":\"light\",\"retryLimit\":3}", calls.getMethod("defaultsJson").invoke(null));
        assertEquals("<Defaults><theme>light</theme><retryLimit>3</retryLimit></Defaults>", calls.getMethod("defaultsXml").invoke(null));
        assertEquals("[{\"id\":\"app\",\"theme\":\"light\",\"count\":0}]", calls.getMethod("stateList").invoke(null));
        assertEquals("<Map><entry><key>default</key><value><theme>light</theme><retryLimit>3</retryLimit></value></entry></Map>", calls.getMethod("defaultsMap").invoke(null));
    }

    @TestFactory
    Stream<DynamicTest> javaSerializationPreservesSingletonIdentity() {
        return Stream.of("Config", "Defaults", "State").map(name -> DynamicTest.dynamicTest(name + " Java serialization", () -> {
            Class<?> type = loader.loadClass("singletons." + name);
            Object singleton = type.getMethod("instance").invoke(null);
            var bytes = new ByteArrayOutputStream();
            try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(singleton); }
            try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
                @Override protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                    return Class.forName(descriptor.getName(), false, loader);
                }
            }) { assertSame(singleton, stream.readObject()); }
        }));
    }

    @TestFactory
    Stream<DynamicTest> typedJsonAndXmlParsingCannotCreateSingletonCopies() {
        return Stream.of("Defaults", "State").map(name -> DynamicTest.dynamicTest(name + " rejects typed deserialization", () -> {
            Class<?> type = loader.loadClass("singletons." + name);
            Object singleton = type.getMethod("instance").invoke(null);
            String json = (String) type.getMethod("toJson").invoke(singleton);
            String xml = (String) type.getMethod("toXml").invoke(singleton);
            assertThrows(JsonMappingException.class, () -> Json.parse(json, type));
            assertThrows(JsonMappingException.class, () -> Xml.parse(xml, type));
            assertThrows(JsonMappingException.class, () -> Json.parseList("[" + json + "]", type));
            assertInstanceOf(Map.class, Json.parse(json));
            assertInstanceOf(Map.class, Xml.parse(xml));
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidSingletonDeclarationsAndConstruction() {
        List<String> invalid = List.of(
                "singleton OldSyntax {}",
                "singleton interface Bad {}",
                "singleton enum Bad {}",
                "singleton class Bad<T> {}",
                "singleton model Bad<T>(T value = null) {}",
                "singleton record Bad<T>(T value = null) {}",
                "singleton record Bad(String name) {}",
                "singleton model Bad(int count) {}",
                "singleton record Bad(int count = \"bad\") {}",
                "singleton model Bad(String name = null) {}",
                "singleton class Bad { Bad() {} }",
                "singleton class Bad { Bad(int value) {} }",
                "singleton record Bad(int value = 1) { Bad() {} }",
                "singleton model Bad(int value = 1) { Bad() {} }",
                "singleton class Bad { static Bad instance() { return null; } }",
                "singleton model Bad(int value = 1) { Bad copy() { return null; } }",
                "singleton record Bad(int instance = 1) {}",
                "singleton record Bad(int value = 1) { int other = 0; }",
                "singleton class Bad { Object clone() { return null; } }",
                "singleton class Bad {} class Use { Object run() { return new Bad(); } }",
                "singleton record Bad(int value = 1) {} class Use { Object run() { return new Bad(1); } }",
                "singleton model Bad(int value = 1) {} class Use { Object run() { return new Bad(1); } }",
                "singleton record Bad(int value = 1) {} class Use { void run() { Bad.instance().value(2); } }",
                "singleton model Bad(int value = 1) {} class Use { Object run() { return Bad.instance().copy(2); } }"
        );
        return invalid.stream().map(text -> DynamicTest.dynamicTest(text, () -> {
            var result = new AccentCompiler().check(TestSources.sources(text));
            assertFalse(result.successful(), text);
            assertFalse(result.diagnostics().isEmpty());
        }));
    }

    @Test
    void dependencyClasspathSingletonsAlsoRejectNew() throws Exception {
        Path source = tempDir.resolve("Consumer.accent");
        for (String name : List.of("Config", "Defaults", "State")) {
            String args = name.equals("Config") ? "" : name.equals("Defaults") ? "\"dark\", 4" : "\"id\", \"dark\", 0";
            Files.writeString(source, "import singletons." + name + "; class Consumer { Object run() { return new " + name + "(" + args + "); } }");
            var result = new AccentCompiler().compile(List.of(source), tempDir.resolve("consumer"), List.of(runtime, output.toString()), tempDir.resolve("consumer-java"), true);
            assertFalse(result.successful());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Cannot construct singleton")), () -> result.diagnostics().toString());
        }
    }

    @TestFactory
    Stream<DynamicTest> javaConsumersCannotConstructOrExtendAnyVariant() {
        return Stream.of("Config", "Defaults", "State").flatMap(name -> Stream.of(false, true).map(extend ->
                DynamicTest.dynamicTest(name + (extend ? " rejects Java inheritance" : " rejects Java new"), () -> {
                    Path sources = Files.createDirectory(tempDir.resolve(name + (extend ? "-extends" : "-new")));
                    Path source = sources.resolve("IllegalUse.java");
                    String arguments = name.equals("Config") ? "" : name.equals("Defaults") ? "\"dark\", 4" : "\"app\", \"dark\", 0";
                    String body = extend ? "public class IllegalUse extends " + name + " {}"
                            : "public class IllegalUse { public Object create() { return new " + name + "(" + arguments + "); } }";
                    Files.writeString(source, "import singletons." + name + "; " + body);
                    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
                    var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
                    try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                        boolean successful = compiler.getTask(null, manager, diagnostics,
                                List.of("-classpath", output + File.pathSeparator + runtime, "-d", sources.toString()),
                                null, manager.getJavaFileObjects(source)).call();
                        assertFalse(successful);
                        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR));
                    }
                })));
    }

    @Test
    void compiledProgramRunsInSeparateJvm() throws Exception {
        Path log = tempDir.resolve("program.txt");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", output + File.pathSeparator + runtime, "singletons.Calls").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> log.toString());
            assertEquals(List.of("{\"theme\":\"light\",\"retryLimit\":3}", "<State><id>app</id><theme>dark</theme><count>0</count></State>"), Files.readAllLines(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
