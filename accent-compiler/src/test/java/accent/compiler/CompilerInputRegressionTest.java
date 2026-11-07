package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompilerInputRegressionTest {
    @TempDir
    Path directory;

    @Test
    void rejectsAnExplicitMissingSource() {
        Path missing = directory.resolve("Missing.accent");

        CompilationResult result = compile(List.of(missing));

        assertFalse(result.successful(), "A missing input must not be reported as successfully compiled");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-C002")
                && d.message().contains("Missing.accent")), () -> result.diagnostics().toString());
        assertFalse(Files.exists(directory.resolve("classes")));
    }

    @Test
    void rejectsMissingInputsBeforePartiallyCompilingOtherSources() throws Exception {
        Path valid = write("src/Valid.accent", "class Valid {}");

        CompilationResult result = compile(List.of(valid, directory.resolve("missing-sources")));

        assertFalse(result.successful(), "Every explicitly requested input must exist");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("missing-sources")));
        assertFalse(Files.exists(directory.resolve("generated/Valid.java")));
        assertFalse(Files.exists(directory.resolve("classes/Valid.class")));
    }

    @Test
    void overlappingDirectoryAndFileInputsCompileEachPhysicalPathOnce() throws Exception {
        Path source = write("src/demo/Answer.accent", "package demo; class Answer { static int value() { return 42; } }");

        CompilationResult result = compile(List.of(directory.resolve("src"), source,
                source.getParent().resolve(".").resolve(source.getFileName())));

        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()})) {
            assertEquals(42, loader.loadClass("demo.Answer").getMethod("value").invoke(null));
        }
    }

    private CompilationResult compile(List<Path> sources) {
        return new AccentCompiler().compile(sources, directory.resolve("classes"),
                List.of("build/classes/java/main"), directory.resolve("generated"), true);
    }

    private Path write(String relative, String source) throws Exception {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }
}
