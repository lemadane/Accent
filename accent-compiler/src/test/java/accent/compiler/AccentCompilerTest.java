package accent.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import accent.source.SourceFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccentCompilerTest {
    @Test
    void acceptsLexicallyValidAccentSource() {
        SourceFile source = new SourceFile(
                Path.of("Hello.accent"),
                "public class Hello { final name = \"Accent\"; }");

        CompilationResult result = new AccentCompiler().check(source);

        assertTrue(result.successful());
        assertTrue(result.compilationUnit().isPresent());
    }

    @Test
    void rejectsWrongFileExtension() {
        CompilationResult result = new AccentCompiler().check(Path.of("Hello.java"));

        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("ACCENT-C001")));
    }
}
