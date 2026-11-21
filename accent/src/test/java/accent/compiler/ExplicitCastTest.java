package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitCastTest {
    @TempDir Path root;

    private CompilationResult compile(String body) throws Exception {
        Path source = root.resolve("Casts.accent");
        Files.writeString(source, "public class Casts { " + body + " }");
        return new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
    }

    private URLClassLoader loader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
    }

    @Test void unionAndNumericCastsExecute() throws Exception {
        var result = compile("""
            public static int age() {
                String|int nameAge = 10
                return nameAge as int + 2 * 3
            }
            public static String name() {
                String|int value = "Ada"
                return value as String
            }
            public static int length() {
                String|int value = "Ada"
                return (value as String).length()
            }
            public static int numeric() { return 300 as byte as int }
            public static int negative() { return -300 as byte }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            var type = loader.loadClass("Casts");
            assertEquals(16, type.getMethod("age").invoke(null));
            assertEquals("Ada", type.getMethod("name").invoke(null));
            assertEquals(3, type.getMethod("length").invoke(null));
            assertEquals(44, type.getMethod("numeric").invoke(null));
            assertEquals(-44, type.getMethod("negative").invoke(null));
        }
    }

    @Test void wrongUnionAlternativeThrowsRatherThanConverting() throws Exception {
        var result = compile("""
            public static String run() {
                String|int nameAge = 10
                return nameAge as String
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            var method = loader.loadClass("Casts").getMethod("run");
            var error = assertThrows(InvocationTargetException.class, () -> method.invoke(null));
            assertInstanceOf(ClassCastException.class, error.getCause());
        }
    }

    @Test void castsHonorNullability() throws Exception {
        var result = compile("""
            public static String required(Object? value) { return value? as String }
            public static String? optional(Object? value) { return value? as String? }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            var type = loader.loadClass("Casts");
            var error = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("required", Object.class).invoke(null, new Object[]{null}));
            assertInstanceOf(NullPointerException.class, error.getCause());
            assertNull(type.getMethod("optional", Object.class).invoke(null, new Object[]{null}));
        }
    }

    @Test void rejectsImpossibleAndVoidCasts() throws Exception {
        assertFalse(compile("static int run() { return true as int }").successful());
        assertFalse(compile("static void run() { 10 as void }").successful());
        assertFalse(compile("static int run() { return \"Ada\" as int }").successful());
    }

    @Test void rejectsParenthesizedJavaCast() throws Exception {
        var result = compile("static int run(String|int value) { return (int) value }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("use 'value as Type'")));
    }

    @Test void castDoesNotNarrowOriginalVariable() throws Exception {
        assertFalse(compile("""
            static int run(String|int value) {
                int age = value as int
                return value
            }
            """).successful());
    }
}
