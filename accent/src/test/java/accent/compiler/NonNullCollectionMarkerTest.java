package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NonNullCollectionMarkerTest {
    @TempDir Path temp;

    private void rejects(String source, String message) {
        var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("Sample.accent"), source)));
        assertFalse(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(message)),
                () -> result.diagnostics().toString());
    }

    @Test void requiresMarkerOnEveryBindingAndMethodUse() {
        rejects("class Sample { void run(List<String> names!?) { names.add(null) } }", "requires '!?' at every use");
        rejects("class Sample { void run(List<String> names!?) { names?.add(null) } }", "requires '!?' at every use");
        rejects("class Sample { List<String> names!? = new java.util.ArrayList<>() void run() { this.names.add(null) } }", "requires '!?' at every use");
        rejects("class Sample { List<String> getNames()!? { return new java.util.ArrayList<String?>() } void run() { this.getNames() } }", "requires '!?' at every use");
        rejects("class Sample { void run(List<String?> names) { names!?.add(null) } }", "does not declare an '!?' marker");
        rejects("class Sample { List<String?> getNames() { return new java.util.ArrayList<String?>() } void run() { this.getNames()!? } }", "does not declare an '!?' marker");
    }

    @Test void rejectsNullCollectionsAndNonCollectionMarkers() {
        rejects("class Sample { void run() { List<String> names!? = null } }", "Incompatible types");
        rejects("class Sample { void run() { List<String> names!? = new java.util.ArrayList<>() names!? = null } }", "Incompatible types");
        rejects("class Sample { List<String> getNames()!? { return null } }", "non-null");
        rejects("class Sample { void run(List<String> names!?) { String name? = names!?.get(0) } }", "Nullable result requires");
        rejects("class Sample { void run(String name!?) {} }", "requires a reference array or List<T>");
        rejects("class Sample { int count()!? { return 1 } }", "requires a reference array or List<T>");
        rejects("class Sample { void run(int[] values!?) {} }", "requires a reference array or List<T>");
    }

    @Test void acceptsBodylessMethodContracts() {
        var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("Sample.accent"), """
                interface Sample {
                    List<String> getNames()!?
                    String[] getAliases()!? throws Exception
                }
                """)));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void optionalChainingSuppliesMethodResultMarkerAndSkipsCalls() throws Exception {
        Path source = temp.resolve("Sample.accent");
        Files.writeString(source, """
                import java.util.List
                import java.util.ArrayList
                class Sample {
                    static int calls = 0
                    static int indices = 0
                    List<String> names!? = new ArrayList<>()
                    List<String> getNames()!? {
                        static.calls++
                        return this.names!?
                    }
                    static int index() { static.indices++ return 0 }
                    static String read(Sample directory?)? {
                        String name? = directory?.getNames()?.get(0)?
                        return name?
                    }
                    static String absent()? { return static.read(null)? }
                    static String absentWithArgument()? {
                        Sample directory? = null
                        return directory?.getNames()?.get(static.index())?
                    }
                    static String present(boolean nullElement)? {
                        Sample directory = new Sample()
                        if (nullElement) { directory.names!?.add(null) }
                        else { directory.names!?.add("Alice") }
                        return static.read(directory)?
                    }
                }
                """);
        Path classes = temp.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source), classes,
                List.of(System.getProperty("accent.test.runtimeClasspath")), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            Class<?> sample = loader.loadClass("Sample");
            assertNull(sample.getMethod("absent").invoke(null));
            assertNull(sample.getMethod("absentWithArgument").invoke(null));
            assertEquals(0, sample.getField("calls").get(null));
            assertEquals(0, sample.getField("indices").get(null));
            assertEquals("Alice", sample.getMethod("present", boolean.class).invoke(null, false));
            assertNull(sample.getMethod("present", boolean.class).invoke(null, true));
            assertEquals(2, sample.getField("calls").get(null));
        }
    }

    @Test void compilesAndRunsBindingsFieldsParametersMethodsAndArrays() throws Exception {
        Path source = temp.resolve("Sample.accent");
        Files.writeString(source, """
                import java.util.List
                import java.util.ArrayList
                class Sample {
                    List<String> names!? = new ArrayList<>()
                    List<String> getNames()!? { return this.names!? }
                    String[] getArray()!? { return new String?[] { "Alice", null } }
                    static String first(List<String> names!?)? { return names!?.get(0)? }
                    static String run()? {
                        Sample sample = new Sample()
                        List<String> names!? = sample.getNames()!?
                        names!? = sample.getNames()!?
                        sample.names!? = names!?
                        names!?.add(null)
                        names!?.add("Alice")
                        String missing? = static.first(names!?)?
                        String[] values!? = sample.getArray()!?
                        values!?[1]? = names!?.get(1)?
                        List<String?> alias = names!?
                        final inferred = names!?
                        if (inferred.size() != 2) { throw new IllegalStateException() }
                        return missing? else values!?[1]?
                    }
                }
                """);
        Path classes = temp.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source), classes,
                List.of(System.getProperty("accent.test.runtimeClasspath")), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            assertEquals("Alice", loader.loadClass("Sample").getMethod("run").invoke(null));
        }

        Path consumer = temp.resolve("Consumer.accent");
        Files.writeString(consumer, """
                class Consumer {
                    static String run()? {
                        Sample sample = new Sample()
                        sample.names!?.add("compiled")
                        return sample?.getNames()?.get(0)?
                    }
                }
                """);
        Path consumerClasses = temp.resolve("consumer-classes");
        var consumerResult = new AccentCompiler().compile(List.of(consumer), consumerClasses,
                List.of(classes.toString(), System.getProperty("accent.test.runtimeClasspath")), temp.resolve("consumer-java"), true);
        assertTrue(consumerResult.successful(), () -> consumerResult.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{consumerClasses.toUri().toURL(), classes.toUri().toURL()})) {
            assertEquals("compiled", loader.loadClass("Consumer").getMethod("run").invoke(null));
        }

        Files.writeString(consumer, "class Consumer { void run(Sample sample) { sample.getNames() } }");
        var missingMarker = new AccentCompiler().compile(List.of(consumer), consumerClasses,
                List.of(classes.toString(), System.getProperty("accent.test.runtimeClasspath")), temp.resolve("invalid-java"), true);
        assertFalse(missingMarker.successful());
        assertTrue(missingMarker.diagnostics().stream().anyMatch(d -> d.message().contains("requires '!?' at every use")),
                () -> missingMarker.diagnostics().toString());
    }
}
