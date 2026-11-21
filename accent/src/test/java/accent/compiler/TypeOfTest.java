package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class TypeOfTest {
    @TempDir Path root;

    private CompilationResult compile(String body) throws Exception {
        Path source = root.resolve("TypeQueries.accent");
        Files.writeString(source, "public class TypeQueries { " + body + " }");
        return new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
    }

    private URLClassLoader loader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
    }

    @Test void unionQueryReportsCurrentValueAndSavedTypeStaysUnchanged() throws Exception {
        var result = compile("""
            public static boolean run() {
                String|int nameAge = 0
                final type = typeof nameAge
                nameAge = "Ada"
                return type == int.class and typeof nameAge == String.class
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            assertEquals(true, loader.loadClass("TypeQueries").getMethod("run").invoke(null));
        }
    }

    @Test void returnsActualClassObjects() throws Exception {
        var result = compile("""
            public static Class integer() { return typeof 0 }
            public static Class text() { return typeof "Ada" }
            public static Class classObject() { return typeof String.class }
            public static Class expression() { return typeof (1 + 2) }
            public static Class query(Object value) { return typeof value }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            var type = loader.loadClass("TypeQueries");
            assertSame(int.class, type.getMethod("integer").invoke(null));
            assertSame(String.class, type.getMethod("text").invoke(null));
            assertSame(Class.class, type.getMethod("classObject").invoke(null));
            assertSame(int.class, type.getMethod("expression").invoke(null));
            assertSame(java.util.ArrayList.class, type.getMethod("query", Object.class).invoke(null, new java.util.ArrayList<String>()));
            assertSame(int[].class, type.getMethod("query", Object.class).invoke(null, new int[]{1}));
        }
    }

    @Test void evaluatesOperandOnce() throws Exception {
        var result = compile("""
            static int calls = 0
            static String|int next() {
                static.calls = static.calls + 1
                return 10
            }
            public static boolean run() {
                final type = typeof static.next()
                return type == int.class and static.calls == 1
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            assertEquals(true, loader.loadClass("TypeQueries").getMethod("run").invoke(null));
        }
    }

    @Test void nullHasNoRuntimeType() throws Exception {
        var result = compile("public static Class query(Object? value) { return typeof value? }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = loader()) {
            var method = loader.loadClass("TypeQueries").getMethod("query", Object.class);
            var error = assertThrows(InvocationTargetException.class, () -> method.invoke(null, new Object[]{null}));
            assertInstanceOf(NullPointerException.class, error.getCause());
        }
    }

    @Test void rejectsVoidOperand() throws Exception {
        var result = compile("""
            static void action() {}
            static Class query() { return typeof static.action() }
            """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("typeof requires a value")));
    }
}
