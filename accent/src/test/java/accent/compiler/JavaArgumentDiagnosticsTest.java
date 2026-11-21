package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaArgumentDiagnosticsTest {
    @TempDir Path root;

    private CompilationResult check(String expression) {
        return new AccentCompiler().check(new SourceFile(Path.of("Sample.accent"),
                "import java.util.*\nclass Sample { static void run() { " + expression + " } }"));
    }

    @Test void invalidArgumentInTryExpressionIsReportedAtOriginalCall() throws Exception {
        Path source = root.resolve("Sample.accent");
        Files.writeString(source, """
                class Sample {
                    static void main() {
                        final boolean text = true
                        final String | int result = try {
                            yield Integer.parseInt(text)
                        } catch (NumberFormatException exception) {
                            yield "Invalid number"
                        } catch (Exception exception) {
                            yield "Invalid"
                        }
                        println(result)
                    }
                }
                """);
        var checked = new AccentCompiler().check(source);
        var error = checked.diagnostics().stream().filter(d -> d.code().equals("ACCENT-S001")).findFirst().orElseThrow();
        assertEquals(5, error.line());
        assertTrue(error.message().contains("java.lang.Integer.parseInt"));
        assertTrue(error.message().contains("(boolean)"));
        assertTrue(error.message().contains("java.lang.String"));
        var compiled = new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
        assertFalse(compiled.successful());
        assertTrue(compiled.diagnostics().stream().noneMatch(d -> d.code().equals("ACCENT-C003")));
        assertFalse(Files.exists(root.resolve("generated/Sample.java")));
    }

    @Test void rejectsWrongArityAndIncompatibleOverloads() {
        assertFalse(check("Integer.parseInt()").successful());
        assertFalse(check("Integer.parseInt(\"1\", true)").successful());
        assertFalse(check("Math.max(true, false)").successful());
        assertFalse(check("String.join(\",\", true)").successful());
    }

    @Test void retainsValidOverloadsBoxingWideningVarargsAndGenericCalls() {
        for (String expression : List.of("Integer.parseInt(\"42\")", "Integer.parseInt(\"ff\", 16)",
                "Math.max(1, 2 as long)", "Math.nextUp(1)", "Math.nextUp(1 as long)", "Integer.toString(1 as byte)", "String.valueOf(true)",
                "String.format(\"%s %d\", \"hello\", 42)", "String.join(\",\", \"a\", \"b\")",
                "Collections.singletonList(true)", "Arrays.asList(\"a\", \"b\")")) {
            var result = check(expression);
            assertTrue(result.successful(), () -> expression + ": " + result.diagnostics());
        }
    }
}
