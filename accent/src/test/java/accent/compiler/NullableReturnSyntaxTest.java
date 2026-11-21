package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NullableReturnSyntaxTest {
    @TempDir Path temp;

    private CompilationResult check(String source) {
        return new AccentCompiler().check(List.of(new SourceFile(Path.of("Sample.accent"), source)));
    }

    private void rejects(String source, String message) {
        var result = check(source);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(message)),
                () -> result.diagnostics().toString());
    }

    @Test void acceptsParametersThrowsAndBodylessDeclarations() {
        var result = check("""
                interface Sample {
                    String name()?
                    String lookup(String key)? throws Exception
                    java.util.List<String> names()?
                    String[] aliases()?
                }
                """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertEquals(4, result.diagnostics().stream()
                .filter(d -> d.message().contains("may return null")).count());
    }

    @Test void rejectsReturnMarkersBeforeParameters() {
        rejects("class Sample { String name?() { return null } }", "Place the nullable return marker after");
        rejects("class Sample { String name?(String key) { return null } }", "Place the nullable return marker after");
        rejects("interface Sample { String name?() }", "Place the nullable return marker after");
    }

    @Test void preservesReturnContractChecks() {
        rejects("class Sample { int count()? { return 1 } }", "Primitive and void");
        rejects("class Sample { void save()? {} }", "Primitive and void");
        rejects("class Sample { String name?()? { return null } }", "Place the nullable return marker after");
        rejects("class Sample { String name()? { return null } String run()? { return this.name() } }",
                "Nullable result requires");
        rejects("class Sample { String name()? { return null } String run() { return this.name()? } }",
                "must return a non-null value");
    }

    @Test void compilesAndRunsNullableResultsAndOptionalChains() throws Exception {
        Path source = temp.resolve("Sample.accent");
        Files.writeString(source, """
                class Sample {
                    static int calls = 0
                    Sample address(boolean present)? {
                        static.calls++
                        return present then this else null
                    }
                    String city() { return "Manila" }
                    static String read(Sample customer?, boolean present)? {
                        return customer?.address(present)?.city()
                    }
                    static String absentCustomer()? { return static.read(null, true)? }
                    static String absentAddress()? { return static.read(new Sample(), false)? }
                    static String present()? { return static.read(new Sample(), true)? }
                    static String fallback() { return static.absentCustomer()? else "Unknown" }
                }
                """);
        Path classes = temp.resolve("classes");
        var result = new AccentCompiler().compile(List.of(source), classes,
                List.of(System.getProperty("accent.test.runtimeClasspath")), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            Class<?> sample = loader.loadClass("Sample");
            assertNull(sample.getMethod("absentCustomer").invoke(null));
            assertEquals(0, sample.getField("calls").get(null));
            assertNull(sample.getMethod("absentAddress").invoke(null));
            assertEquals(1, sample.getField("calls").get(null));
            assertEquals("Manila", sample.getMethod("present").invoke(null));
            assertEquals(2, sample.getField("calls").get(null));
            assertEquals("Unknown", sample.getMethod("fallback").invoke(null));
        }
    }
}
