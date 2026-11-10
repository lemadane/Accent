package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WhileElseTest {
    @TempDir Path temp;

    @Test void executesElseOnCompletionButNotBreakReturnOrThrow() throws Exception {
        Path source = temp.resolve("WhileElse.accent");
        Files.writeString(source, """
                class WhileElse {
                    static int exit(boolean fail) {
                        int i = 1;
                        while (i > 0) {
                            if (fail) { throw new IllegalStateException("stop"); }
                            return 7;
                        } else { throw new IllegalStateException("else must not run"); }
                        return 0;
                    }
                    static int run() {
                        int score = 0;
                        int i = 0;
                        while (false) { score = 1000; } else { score++; }
                        while (i < 3) { i++; continue; } else { score++; }
                        while (i > 0) { break; } else { score = 1000; }
                        i = 0;
                        while (i < 2) {
                            int j = 1;
                            while (j > 0) { break; } else { score = 1000; }
                            i++;
                        } else { score++; }
                        if (static.exit(false) != 7) { throw new IllegalStateException("return"); }
                        try { static.exit(true); } catch (IllegalStateException e) { score++; }
                        return score;
                    }
                }
                """);
        assertEquals(4, compileAndRun(source, "WhileElse"));
    }

    @Test void loopExpressionIncludesElseYieldsAndSkipsThemAfterBreak() throws Exception {
        Path source = temp.resolve("Values.accent");
        Files.writeString(source, """
                import java.util.List;
                class Values {
                    static int run() {
                        int i = 0;
                        final values = while (i < 2) { i++; yield i; } else { yield 3; };
                        if (not values.equals(List.of(1, 2, 3))) { return 1; }
                        final empty = while (i < 0) { yield 1; } else { yield 9; };
                        if (not empty.equals(List.of(9))) { return 2; }
                        final stopped = while (i > 0) { yield 7; break; } else { yield 8; };
                        if (not stopped.equals(List.of(7))) { return 3; }
                        return 0;
                    }
                }
                """);
        assertEquals(0, compileAndRun(source, "Values"));
    }

    @Test void requiresBracesAndChecksElseScope() {
        for (String body : List.of(
                "while (false) {} else println(1);",
                "while (false) println(1); else {}",
                "while (false) { int hidden = 1; } else { println(hidden); }")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "class Bad { static void run() { " + body + " } }"));
            assertFalse(result.successful(), body);
        }
    }

    private Object compileAndRun(Path source, String name) throws Exception {
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            return loader.loadClass(name).getMethod("run").invoke(null);
        }
    }
}
