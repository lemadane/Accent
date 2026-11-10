package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InferredForInitTest {
    @TempDir Path temp;

    @Test void inferredInitializersWorkInStatementsAndExpressions() throws Exception {
        assertEquals(0, run("""
                int sum = 0;
                for (i = 0; i < 3; i++) { sum = sum + i; } else { sum++; }
                for (i = 0; i < 3; i++) { sum = sum + i; }
                for (i = 0; i < 3; i++) { sum = sum + i; }
                if (sum != 10) { return 1; }
                final values = for (i = static.start(); i < 2; i++) { yield i; } else { yield 2; };
                if (not values.equals(List.of(0, 1, 2))) { return 2; }
                final stopped = for (i = 0; i < 2; i++) { yield i; break; } else { yield 9; };
                if (not stopped.equals(List.of(0))) { return 3; }
                return 0;
                """));
    }

    @Test void existingVariablesAreAssignedInsteadOfShadowed() throws Exception {
        assertEquals(6, run("""
                int i = 99;
                int sum = 0;
                for (i = 0; i < 3; i++) { sum = sum + i; }
                return sum + i;
                """));
    }

    @Test void respectsScopeFinalBindingsAndRequiredInitialization() {
        for (String body : List.of(
                "for (i = 0; i < 1; i++) {} println(i);",
                "for (i = 0; i < 1; i++) {} else { println(i); }",
                "final i = 2; for (i = 0; i < 1; i++) {}",
                "for (i; i < 1; i++) {}",
                "for (i = null; false;) {}")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "class Bad { static void run() { " + body + " } }"));
            assertFalse(result.successful(), body);
        }
    }

    @Test void parametersAndFieldsKeepTheirExistingRules() {
        for (String source : List.of(
                "class Bad { static void run(int i) { for (i = 0; i < 1; i++) {} } }",
                "class Bad { static int i = 0; static void run() { for (i = 0; i < 1; i++) {} } }")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"), source));
            assertFalse(result.successful(), source);
        }
    }

    @Test void rejectsDeclarationKeywordsInTraditionalStatementsAndExpressions() {
        for (String declaration : List.of("var i = 0", "int i = 0", "long i = 0", "final i = 0", "final int i = 0")) {
            for (String prefix : List.of("", "final values = ")) {
                var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                        "class Bad { void run() { " + prefix + "for (" + declaration
                                + "; i < 1; i++) {" + (prefix.isEmpty() ? "" : "yield i;") + "}; } }"));
                assertFalse(result.successful(), declaration);
                assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Traditional for initializers infer their type")),
                        result.diagnostics().toString());
            }
        }
    }

    @Test void javaLoopsRetainTypedAndVarDeclarations() {
        var result = new AccentCompiler().check(new SourceFile(Path.of("JavaLoops.java"), """
                public class JavaLoops {
                    public static int run() {
                        int sum = 0;
                        for (int i = 0; i < 2; i++) { sum += i; }
                        for (var i = 0; i < 2; i++) { sum += i; }
                        return sum;
                    }
                }
                """));
        assertTrue(result.successful(), result.diagnostics().toString());
    }

    private Object run(String body) throws Exception {
        Path source = temp.resolve("Example.accent");
        Files.writeString(source, "import java.util.List; class Example { static int start() { return 0; } static int run() { " + body + " } }");
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            return loader.loadClass("Example").getMethod("run").invoke(null);
        }
    }
}
