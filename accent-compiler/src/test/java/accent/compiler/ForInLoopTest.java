package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForInLoopTest {
    @TempDir Path temp;

    @Test void statementsAndExpressionsPreserveCompletionRules() throws Exception {
        assertEquals(0, run("""
                final numbers = List.of(1, 2);
                int sum = 0;
                for (item in numbers) { sum = sum + item; } else { sum++; }
                if (sum != 4) { return 1; }
                final values = for (item in numbers) { yield item; } else { yield 3; };
                if (not values.equals(List.of(1, 2, 3))) { return 2; }
                final stopped = for (item in numbers) { yield item; break; } else { yield 9; };
                if (not stopped.equals(List.of(1))) { return 3; }
                final List<Integer> empty = List.of();
                final fallback = for (item in empty) { yield item; } else { yield 9; };
                if (not fallback.equals(List.of(9))) { return 4; }
                List<Integer> missing? = null;
                for (item in missing?) { return 5; } else { sum++; }
                int[] array = {3, 4};
                for (item in array) { sum = sum + item; }
                for (Integer item in numbers) { continue; } else { sum++; }
                for (var item : numbers) { sum = sum + item; }
                for (i = 0; i < 1; i++) { sum++; }
                if (sum != 17) { return 6; }
                return 0;
                """));
    }

    @Test void sourceIsEvaluatedOnceAndNullableBindingsKeepTheirMarkers() throws Exception {
        assertEquals(2, run("""
                for (item in static.source()) {} else {}
                if (static.calls != 1) { return 101; }
                for (var item : static.source()) {} else {}
                String[] items?? = {"one", null};
                int missing = 0;
                for (item? in items?) {
                    if (item? == null) { missing++; }
                }
                if (missing != 1) { return 100; }
                return static.calls;
                """));
    }

    @Test void bindingIsImmutableAndBodiesAreBraced() {
        for (String statement : List.of(
                "for (item in List.of(1)) { item = 2; }",
                "for (final item in List.of(1)) { item = 2; }",
                "for (Integer item in List.of(1)) { item = 2; }",
                "for (item in List.of(1)) { item++; }",
                "for (item in List.of(1)) println(item);",
                "for (item in List.of(1)) {} else println(0);",
                "for (item in List.of(1)) {} else { println(item); }",
                "each (item from List.of(1)) {}",
                "for (item from List.of(1)) {}")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "import java.util.List; class Bad { static void run() { " + statement + " } }"));
            assertFalse(result.successful(), statement);
        }
    }

    @Test void nestedBreakDoesNotSkipOuterElseAndInCanBeAnIdentifier() throws Exception {
        assertEquals(4, run("""
                final in = List.of(1, 2);
                int count = 0;
                for (item in in) {
                    for (nested in in) { break; } else { return 100; }
                    count++;
                } else { count++; }
                for (final item in List.of(1)) { count++; }
                return count;
                """));
    }

    @Test void contextualInDoesNotChangeTraditionalOrColonLoops() throws Exception {
        assertEquals(6, run("""
                int count = 0;
                {
                    final in = 2;
                    for (i = in; i < 4; i++) { count++; }
                }
                for (var in : List.of(1, 2)) { count++; }
                for (Integer in in List.of(1, 2)) { count++; }
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
