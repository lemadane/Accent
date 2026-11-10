package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ThenElseExpressionTest {
    @TempDir Path temp;

    @Test void conditionalsPreservePrecedenceTruthinessAndLazyEvaluation() throws Exception {
        Path file = temp.resolve("Example.accent");
        Files.writeString(file, """
                class Example {
                    static int calls = 0;
                    static int next() { static.calls++; return 7; }
                    static int then() { return 4; }
                    static String run() {
                        final a = false then 1 else true then 2 else 3;
                        final b = true then false then 1 else 2 else 3;
                        final c = 1 + 2 == 3 and true then 4 else 5;
                        final d = true then 6 else static.next();
                        final e = false then static.next() else 8;
                        final f = true then static.next() else 9;
                        String name? = null;
                        final g = name? then "present" else "absent";
                        final h = name? else "fallback";
                        String chosen? = true then name? else "other";
                        String nested? = true then false then "x" else name? else "z";
                        final text = $"{a}:{b}:{c}:{d}:{e}:{f}:{static.calls}:{g}:{h}:{chosen? else "null"}:{nested? else "null"}:{static.then()}";
                        return text;
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(file), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            assertEquals("2:2:4:6:8:7:1:absent:fallback:null:null:4", loader.loadClass("Example").getMethod("run").invoke(null));
        }
    }

    @Test void rejectsOldSyntaxMissingBranchesAndNullableNonNullAssignments() {
        for (String body : List.of(
                "final value = true ? 1 : 2;",
                "final value = true then 1;",
                "final value = true then else 2;",
                "final value = true then 1 else;",
                "String name? = null; String value = true then name? else \"fallback\";")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "class Bad { void run() { " + body + " } }"));
            assertFalse(result.successful(), body);
        }
    }

    @Test void javaRetainsTernariesAndThenIdentifiers() {
        var result = new AccentCompiler().check(new SourceFile(Path.of("JavaExample.java"), """
                public class JavaExample {
                    public int then() { return 1; }
                    public int run(boolean flag) { return flag ? this.then() : 2; }
                }
                """));
        assertTrue(result.successful(), result.diagnostics().toString());
    }
}
