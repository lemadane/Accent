package accent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryCheckTest {
    @TempDir
    Path directory;

    @Test
    void checksRecursiveSourcesTogetherAndIgnoresOtherFiles() throws Exception {
        write("Caller.accent", "package demo; class Caller { int call() { return new Answer().value(); } }");
        write("nested/Answer.accent", "package demo; class Answer { int value() { return 42; } }");
        write("notes.txt", "This is not Accent source.");

        Result result = run("check", directory.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("Accent sources are valid (2 files)." + System.lineSeparator(), result.output());
        assertEquals("", result.error());
    }

    @Test
    void attributesSemanticErrorsToTheirRelativeSourcePaths() throws Exception {
        write("Good.accent", "class Good {}");
        write("nested/Broken.accent", "class Broken { void run() { final value = 1; value = 2; } }");

        Result result = run("check", directory.toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains(Path.of("nested", "Broken.accent") + ":1:"), result.error());
        assertFalse(result.error().contains("Good.accent"), result.error());
    }

    @Test
    void reportsAllSyntaxErrorsInStablePathOrder() throws Exception {
        write("z/Z.accent", "class Z { # }");
        write("a/A.accent", "class A { # }");

        Result result = run("check", directory.toString());

        assertEquals(1, result.exitCode());
        int first = result.error().indexOf(Path.of("a", "A.accent") + ":");
        int second = result.error().indexOf(Path.of("z", "Z.accent") + ":");
        assertTrue(first >= 0, result.error());
        assertTrue(second > first, result.error());
        assertTrue(result.error().contains("ACCENT-L003"), result.error());
    }

    @Test
    void rejectsDirectoriesWithoutAccentFiles() throws Exception {
        write("readme.txt", "No source files here.");

        Result result = run("check", directory.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("No .accent files found"), result.error());
        assertEquals("", result.output());
    }

    @Test
    void retainsSingleFileChecking() throws Exception {
        Path file = write("Hello.accent", "class Hello {}");

        Result result = run("check", file.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("Accent source is valid"), result.output());
    }

    @Test
    void rejectsInaccessibleMembersAndReportsTheCallingFile() throws Exception {
        write("owner/Counter.accent", "package owner; class Counter { private int _cache = 1; }");
        write("other/Caller.accent", "package other; import owner.Counter; class Caller { int call(Counter! counter) { return counter._cache; } }");

        Result result = run("check", directory.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.error().contains(Path.of("other", "Caller.accent") + ":1:"), result.error());
        assertTrue(result.error().contains("_cache"), result.error());
        assertEquals("", result.output());
    }

    @Test
    void compileRejectsMissingTargetsWithoutPrintingSuccess() {
        Path missing = directory.resolve("Missing.accent");

        Result result = run("compile", missing.toString(),
                "-d", directory.resolve("classes").toString(),
                "--save-java", directory.resolve("generated").toString());

        assertEquals(1, result.exitCode(), result.output());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Missing.accent"), result.error());
    }

    private Path write(String relative, String source) throws Exception {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static Result run(String... arguments) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = AccentCli.run(arguments);
            return new Result(exitCode, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String output, String error) {}
}
