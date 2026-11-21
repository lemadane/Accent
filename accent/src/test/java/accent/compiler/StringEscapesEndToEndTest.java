package accent.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StringEscapesEndToEndTest {
    @TempDir Path root;

    @Test
    void printAndPrintlnDecodeStringEscapesExactlyOnce() throws Exception {
        Path source = root.resolve("StringEscapes.accent");
        Files.writeString(source, """
                class StringEscapes {
                    static void main(String[] args) {
                        print("\\nHello, Accent!\\n")
                        println("first\\nsecond")
                        final text = "\\t\\b\\r\\f\\\"\\'\\\\n"
                        print(text)
                        print(\"\"\"block\\nline\"\"\")
                        print($"\\ninterpolated {1}")
                    }
                }
                """);
        Path classes = root.resolve("classes");
        String runtimeClasspath = System.getProperty("accent.test.runtimeClasspath");
        var result = new AccentCompiler().compile(List.of(source), classes,
                List.of(runtimeClasspath), root.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes + java.io.File.pathSeparator + runtimeClasspath, "StringEscapes")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertEquals("\nHello, Accent!\nfirst\nsecond" + System.lineSeparator()
                + "\t\b\r\f\"'\\nblock\nline\ninterpolated 1", output);
    }
}
