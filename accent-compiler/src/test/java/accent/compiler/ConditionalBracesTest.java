package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalBracesTest {
    @TempDir Path temp;

    @Test void rejectsUnbracedBranchesIncludingEmptyStatements() {
        for (String body : List.of(
                "if (true) return;",
                "if (true) ;",
                "if (true) { return; } else return;",
                "if (true) {} else ;",
                "if (true) {} else if (false) return;",
                "if (true) if (false) {} else {}",
                "if (true) // comment\n return;",
                "for (int i = 0; i < 1; i++) {} else return;",
                "for (String value : new String[]{}) {} else return;")) {
            var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("Sample.accent"),
                    "class Sample { void run() { " + body + " } }")));
            assertFalse(result.successful(), body);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("requires braces")),
                    () -> body + ": " + result.diagnostics());
        }
    }

    @Test void acceptsBracedBranchesAndElseIfChains() throws Exception {
        Path source = temp.resolve("Sample.accent");
        Files.writeString(source, """
                class Sample {
                    static int classify(int value) {
                        if (value > 0) {
                            return 1;
                        } else if (value < 0) {
                            if (value < -10) { return -2; }
                            else { return -1; }
                        } else {
                            return 0;
                        }
                    }
                    void empty(boolean flag) { if (flag) {} else {} }
                    int expression(boolean flag) {
                        return if (flag) { yield 1; } else { yield 0; };
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            var method = loader.loadClass("Sample").getMethod("classify", int.class);
            assertEquals(1, method.invoke(null, 2));
            assertEquals(-1, method.invoke(null, -2));
            assertEquals(-2, method.invoke(null, -20));
            assertEquals(0, method.invoke(null, 0));
        }
    }

    @Test void javaInteropKeepsJavaBranchSyntax() {
        var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("JavaSample.java"),
                "public class JavaSample { public int run(boolean flag) { if (flag) return 1; else return 0; } }")));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
}
