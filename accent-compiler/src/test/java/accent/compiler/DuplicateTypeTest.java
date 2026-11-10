package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateTypeTest {
    @TempDir
    Path tempDir;

    @Test
    void checkAttributesDuplicateTypesToTheirSourceFile() {
        SourceFile original = new SourceFile(Path.of("first/A.accent"), "package vis; class A {}");
        SourceFile duplicate = new SourceFile(Path.of("second/A.accent"), "package vis; model A(int value)");
        var diagnostics = new AccentCompiler().checkEach(List.of(original, duplicate));

        assertTrue(diagnostics.get(original).isEmpty());
        assertTrue(diagnostics.get(duplicate).stream().anyMatch(d -> d.code().equals("ACCENT-C006")
                && d.message().contains("Duplicate type 'vis.A'") && d.message().contains("first/A.accent")));
        assertFalse(new AccentCompiler().check(List.of(original, duplicate)).successful());
    }

    @Test
    void compileRejectsDuplicateTypesBeforeGeneratedSourcesAreOverwritten() throws Exception {
        Path first = tempDir.resolve("src/first/A.accent");
        Path second = tempDir.resolve("src/second/A.accent");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "package vis; class A { int first = 1; }");
        Files.writeString(second, "package vis; class A { int second = 2; }");

        CompilationResult result = new AccentCompiler().compile(List.of(tempDir.resolve("src")),
                tempDir.resolve("classes"), List.of("build/classes/java/main"), tempDir.resolve("generated"), true);

        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-C006")));
        assertFalse(Files.exists(tempDir.resolve("generated/vis/A.java")));
    }

    @Test
    void sameSimpleNameInDifferentPackagesRemainsValid() {
        CompilationResult result = new AccentCompiler().check(List.of(
                new SourceFile(Path.of("first/A.accent"), "package first; class A {}"),
                new SourceFile(Path.of("second/A.accent"), "package second; class A {}")));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void duplicateExtensionHelpersAcrossSourcesAreRejectedBeforeOverwrite() throws Exception {
        Path first = tempDir.resolve("src/first.accent");
        Path second = tempDir.resolve("src/second.accent");
        Files.createDirectories(first.getParent());
        String firstSource = "package vis; extension String { int first() { return 1; } }";
        String secondSource = "package vis; extension String { int second() { return 2; } }";
        Files.writeString(first, firstSource);
        Files.writeString(second, secondSource);

        var checked = new AccentCompiler().checkEach(List.of(
                new SourceFile(first, firstSource), new SourceFile(second, secondSource)));
        assertTrue(checked.get(new SourceFile(second, secondSource)).stream()
                .anyMatch(d -> d.code().equals("ACCENT-C007")));

        CompilationResult result = new AccentCompiler().compile(List.of(tempDir.resolve("src")),
                tempDir.resolve("classes"), List.of("build/classes/java/main"), tempDir.resolve("generated"), true);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-C007")));
        assertFalse(Files.exists(tempDir.resolve("generated/vis/Extension_String.java")));
    }

    @Test
    void extensionHelpersCannotOverwriteAnOrdinaryType() {
        CompilationResult result = new AccentCompiler().check(new SourceFile(Path.of("Extension_String.accent"), """
                class Extension_String {}
                extension String { int value() { return 1; } }
                """));
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-C007")));
    }
}
