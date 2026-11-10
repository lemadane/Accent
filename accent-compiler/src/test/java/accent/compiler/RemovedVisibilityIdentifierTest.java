package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemovedVisibilityIdentifierTest {
    @Test
    void rejectsJavaVisibilityWordsAsNamesBeforeJavaEmission() {
        for (String name : List.of("public", "private", "protected")) {
            for (String source : List.of(
                    "class A { int " + name + " = 1; }",
                    "class A { " + name + " = 1; }",
                    "class A { void " + name + "() {} }",
                    "class A { void run(int " + name + ") {} }",
                    "class A { void run() { var " + name + " = 1; } }",
                    "model A(int " + name + ") {}",
                    "class " + name + " {}",
                    "class A { void run() { var callback = (" + name + ") -> 1; } }")) {
                CompilationResult result = new AccentCompiler().check(new SourceFile(Path.of("A.accent"), source));
                assertFalse(result.successful(), source);
                assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(
                                "'" + name + "' cannot be used as an identifier because it is reserved by Java")),
                        () -> source + ": " + result.diagnostics());
            }
        }
    }

    @Test
    void permitsVisibilityWordsInStringsAndLongerIdentifiers() {
        CompilationResult result = new AccentCompiler().check(new SourceFile(Path.of("A.accent"), """
                class A {
                    int privateCount = 1;
                    String describe!(int _public) {
                        var internal = "private public protected";
                        return internal;
                    }
                }
                """));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
}
