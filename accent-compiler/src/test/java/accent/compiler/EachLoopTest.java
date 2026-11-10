package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EachLoopTest {
    @TempDir Path temp;

    @Test void statementsAndExpressionsPreserveCompletionRules() throws Exception {
        assertEquals(0, run("""
                final numbers = List.of(1, 2);
                int sum = 0;
                each (item from numbers) { sum = sum + item; } else { sum++; }
                if (sum != 4) { return 1; }
                final values = each (item from numbers) { yield item; } else { yield 3; };
                if (not values.equals(List.of(1, 2, 3))) { return 2; }
                final stopped = each (item from numbers) { yield item; break; } else { yield 9; };
                if (not stopped.equals(List.of(1))) { return 3; }
                final List<Integer> empty = List.of();
                final fallback = each (item from empty) { yield item; } else { yield 9; };
                if (not fallback.equals(List.of(9))) { return 4; }
                List<Integer> missing? = null;
                each (item from missing?) { return 5; } else { sum++; }
                int[] array = {3, 4};
                each (item from array) { sum = sum + item; }
                each (Integer item from numbers) { continue; } else { sum++; }
                for (var item : numbers) { sum = sum + item; }
                for (i = 0; i < 1; i++) { sum++; }
                if (sum != 17) { return 6; }
                return 0;
                """));
    }

    @Test void sourceIsEvaluatedOnceAndNullableBindingsKeepTheirMarkers() throws Exception {
        assertEquals(2, run("""
                each (item from static.source()) {} else {}
                if (static.calls != 1) { return 101; }
                for (var item : static.source()) {} else {}
                String[] items?? = {"one", null};
                int missing = 0;
                each (item? from items?) {
                    if (item? == null) { missing++; }
                }
                if (missing != 1) { return 100; }
                return static.calls;
                """));
    }

    @Test void bindingIsImmutableAndBodiesAreBraced() {
        for (String statement : List.of(
                "each (item from List.of(1)) { item = 2; }",
                "each (final item from List.of(1)) { item = 2; }",
                "each (Integer item from List.of(1)) { item = 2; }",
                "each (item from List.of(1)) { item++; }",
                "each (item from List.of(1)) println(item);",
                "each (item from List.of(1)) {} else println(0);",
                "each (item from List.of(1)) {} else { println(item); }",
                "each (item : List.of(1)) {}")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "import java.util.List; class Bad { static void run() { " + statement + " } }"));
            assertFalse(result.successful(), statement);
        }
    }

    @Test void nestedBreakDoesNotSkipOuterElseAndFromCanBeAnIdentifier() throws Exception {
        assertEquals(4, run("""
                final from = List.of(1, 2);
                int count = 0;
                each (item from from) {
                    each (nested from from) { break; } else { return 100; }
                    count++;
                } else { count++; }
                each (final item from List.of(1)) { count++; }
                return count;
                """));
    }

    private Object run(String body) throws Exception {
        Path source = temp.resolve("Example.accent");
        Files.writeString(source, "import java.util.List; class Example { static int calls = 0; static int[] source() { static.calls++; int[] values = {1, 2}; return values; } static int run() { " + body + " } }");
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            return loader.loadClass("Example").getMethod("run").invoke(null);
        }
    }
}
