package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StaticImportTest {
    @TempDir Path temp;

    @Test void namedAndWildcardImportsExecuteAndEvaluateArgumentsOnce() throws Exception {
        Path source = temp.resolve("Imported.accent");
        Files.writeString(source, """
                import static java.lang.Math.sqrt;
                import static java.lang.Math.*;
                import static java.lang.Integer.MAX_VALUE;
                class Imported {
                    static int calls = 0;
                    static int next() { static.calls++; return -9; }
                    static double run() {
                        final value = abs(static.next());
                        final root = sqrt(value);
                        return root + static.calls + (MAX_VALUE - 2147483647);
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        String generated = Files.readString(temp.resolve("java/Imported.java"));
        assertTrue(generated.contains("java.lang.Math.sqrt"), generated);
        assertTrue(generated.contains("java.lang.Integer.MAX_VALUE"), generated);
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            assertEquals(4.0, loader.loadClass("Imported").getMethod("run").invoke(null));
        }
    }

    @Test void explicitImportWinsOverWildcardAndLocalsShadowFields() {
        var result = new AccentCompiler().check(new SourceFile(Path.of("Chosen.accent"), """
                import static java.lang.Math.max;
                import static java.lang.Integer.*;
                import static java.lang.Long.*;
                class Chosen {
                    static int run() {
                        int MAX_VALUE = 5;
                        return max(MAX_VALUE, 3);
                    }
                }
                """));
        assertTrue(result.successful(), result.diagnostics().toString());
    }

    @Test void rejectsAmbiguousAndInstanceImports() {
        for (String imports : List.of("import static java.lang.Integer.*; import static java.lang.Long.*;",
                "import static java.lang.Integer.MAX_VALUE; import static java.lang.Long.MAX_VALUE;")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    imports + " class Bad { static long run() { return MAX_VALUE; } }"));
            assertFalse(result.successful());
            assertTrue(result.diagnostics().toString().contains("Ambiguous static import"), result.diagnostics().toString());
        }
        var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                "import static java.lang.String.length; class Bad { static int run() { return length(); } }"));
        assertFalse(result.successful());
    }

    @Test void importedMutableFieldCanBeAssignedAndIncremented() throws Exception {
        Path owner = temp.resolve("Counter.accent");
        Path caller = temp.resolve("Caller.accent");
        Files.writeString(owner, "package sample; class Counter { static int value = 0; }");
        Files.writeString(caller, """
                package sample;
                import static sample.Counter.value;
                class Caller {
                    static int run() { value = 4; value++; return value; }
                }
                """);
        var result = new AccentCompiler().compile(List.of(owner, caller), temp.resolve("mutable"),
                List.of("build/classes/java/main"), temp.resolve("mutable-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("mutable").toUri().toURL()})) {
            assertEquals(5, loader.loadClass("sample.Caller").getMethod("run").invoke(null));
        }
    }
    @Test void validatesUnusedImportsAndAmbiguousCalls() {
        for (String source : List.of(
                "import static java.lang.String.length; class Bad {}",
                "import static missing.Type.value; class Bad {}",
                "import static java.lang.Math.*; import static java.lang.Integer.*; class Bad { static int run() { return max(1, 2); } }")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"), source));
            assertFalse(result.successful(), source);
        }
    }

    @Test void nullableAccentContractsAndInheritedImportsArePreserved() throws Exception {
        Path parent = temp.resolve("Parent.accent");
        Path child = temp.resolve("Child.accent");
        Path caller = temp.resolve("Nullable.accent");
        Files.writeString(parent, "package sample; class Parent { static String label? = null; static String text?() { return static.label?; } }");
        Files.writeString(child, "package sample; class Child extends Parent {}");
        Files.writeString(caller, """
                package sample;
                import static sample.Child.label;
                import static sample.Child.text;
                class Nullable {
                    static String run() { return text()? else (label? else "empty"); }
                }
                """);
        var result = new AccentCompiler().compile(List.of(parent, child, caller), temp.resolve("nullable"),
                List.of("build/classes/java/main"), temp.resolve("nullable-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("nullable").toUri().toURL()})) {
            assertEquals("empty", loader.loadClass("sample.Nullable").getMethod("run").invoke(null));
        }
    }

}
