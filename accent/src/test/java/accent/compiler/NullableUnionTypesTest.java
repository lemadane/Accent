package accent.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullableUnionTypesTest {
    @TempDir Path root;

    private CompilationResult compile(String body) throws Exception {
        Path source = root.resolve("NullableUnion.accent");
        Files.writeString(source, "public class NullableUnion { " + body + " }");
        return new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
    }

    @Test void narrowsNullableUnionInConditionalExpressionsAndIfBranches() throws Exception {
        var result = compile("""
                public static String describe(String|int value?) {
                    final name = value? is String then value? else "Guest"
                    final age = value? is int then value? else 0
                    return name + ":" + age
                }
                public static int measure(String|int value?) {
                    if (value? is String) { return value?.length() }
                    else {
                        if (value? is int) { return value? + 1 }
                        else { return 0 }
                    }
                }
                public static String named(String|int value?) {
                    return value? is String name then name else "Guest"
                }
                public static String empty() {
                    final String|int value? = null
                    return value? is String then value? else "Guest"
                }
                """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("NullableUnion");
            var describe = type.getMethod("describe", Object.class);
            assertEquals("Ada:0", describe.invoke(null, "Ada"));
            assertEquals("Guest:10", describe.invoke(null, 10));
            assertEquals("Guest:0", describe.invoke(null, new Object[]{null}));
            var measure = type.getMethod("measure", Object.class);
            assertEquals(3, measure.invoke(null, "Ada"));
            assertEquals(11, measure.invoke(null, 10));
            assertEquals(0, measure.invoke(null, new Object[]{null}));
            var named = type.getMethod("named", Object.class);
            assertEquals("Ada", named.invoke(null, "Ada"));
            assertEquals("Guest", named.invoke(null, new Object[]{null}));
            assertEquals("Guest", type.getMethod("empty").invoke(null));
        }
    }

    @Test void stillRequiresNullableMarkerInsideNarrowedBranch() throws Exception {
        var result = compile("""
                static String run(String|int value?) {
                    return value? is String then value else "Guest"
                }
                """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("requires '?' at every use")));
    }

    @Test void failedTypeTestDoesNotMakeNullableUnionSafeToUnbox() throws Exception {
        var result = compile("""
                static int run(String|int value?) {
                    return value? is String then 0 else value?
                }
                """);
        assertFalse(result.successful());
    }

    @Test void assignmentInvalidatesNullableUnionNarrowing() throws Exception {
        var result = compile("""
                static int run(String|int value?) {
                    if (value? is int) {
                        value? = null
                        return value?
                    }
                    return 0
                }
                """);
        assertFalse(result.successful());
    }
}
