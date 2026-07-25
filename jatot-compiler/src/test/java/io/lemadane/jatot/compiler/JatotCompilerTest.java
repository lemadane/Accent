package io.lemadane.jatot.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.lemadane.jatot.source.SourceFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JatotCompilerTest {
    @Test
    void acceptsLexicallyValidJatotSource() {
        SourceFile source = new SourceFile(
                Path.of("Hello.jatot"),
                "public class Hello { final name = \"Jatot\"; }");

        CompilationResult result = new JatotCompiler().check(source);

        assertTrue(result.successful());
        assertTrue(result.compilationUnit().isPresent());
    }

    @Test
    void rejectsWrongFileExtension() {
        CompilationResult result = new JatotCompiler().check(Path.of("Hello.java"));

        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("JATOT-C001")));
    }
}
