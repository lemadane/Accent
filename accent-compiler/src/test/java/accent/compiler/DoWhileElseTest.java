package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DoWhileElseTest {
    @TempDir Path temp;

    @Test void statementsRunAtLeastOnceAndRespectBreakAndContinue() throws Exception {
        assertEquals(4, run("""
                int score = 0;
                int i = 0;
                do { i++; } while (false) else { score++; }
                if (i != 1) { return 100; }
                do { i++; continue; } while (i < 3) else { score++; }
                do { break; } while (true) else { return 101; }
                do {
                    int j = 0;
                    do { j++; break; } while (j < 2) else { return 102; }
                    i++;
                } while (i < 4) else { score++; }
                // A nested loop's completion block can break the enclosing loop.
                do {
                    do {} while (false) else { break; }
                } while (true) else { return 103; }
                do { score++; } while (false);
                return score;
                """));
    }

    @Test void expressionsCollectBodyAndElseValues() throws Exception {
        assertEquals(0, run("""
                int i = 0;
                final values = do { i++; yield i; } while (i < 2) else { yield 3; };
                if (not values.equals(List.of(1, 2, 3))) { return 1; }
                final once = do { yield 7; } while (false) else { yield 9; };
                if (not once.equals(List.of(7, 9))) { return 2; }
                final stopped = do { yield 7; break; } while (true) else { yield 9; };
                if (not stopped.equals(List.of(7))) { return 3; }
                final plain = do { yield 4; } while (false);
                if (not plain.equals(List.of(4))) { return 4; }
                return 0;
                """));
    }

    @Test void returnsAndExceptionsBypassElse() throws Exception {
        assertEquals(7, run("""
                boolean leave = true;
                do { if (leave) { return 7; } } while (false) else { return 9; }
                return 10;
                """));
        assertEquals(0, run("""
                boolean fail = true;
                try {
                    do { if (fail) { throw new IllegalStateException("stop"); } }
                    while (false) else { return 1; }
                } catch (IllegalStateException e) { return 0; }
                return 2;
                """));
    }

    @Test void rejectsUnbracedAndOutOfScopeElseBodies() {
        for (String body : List.of(
                "do {} while (false) else println(1);",
                "do println(1); while (false) else {}",
                "do { int hidden = 1; } while (false) else { println(hidden); }")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "class Bad { static void run() { " + body + " } }"));
            assertFalse(result.successful(), body);
        }
    }

    private Object run(String body) throws Exception {
        Path source = temp.resolve("Example.accent");
        Files.writeString(source, "import java.util.List; class Example { static int run() { " + body + " } }");
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            return loader.loadClass("Example").getMethod("run").invoke(null);
        }
    }
}
