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
    void supportsFinalVarAndFinalShorthandAndVar() {
        // 1. final var name = "John Doe"; (immutable)
        SourceFile src1 = new SourceFile(
                Path.of("Test1.accent"),
                "public class Test1 { public void run() { final var name = \"John Doe\"; } }");
        assertTrue(new AccentCompiler().check(src1).successful());

        // 2. final name = "John Doe"; (shorthand for final var, immutable)
        SourceFile src2 = new SourceFile(
                Path.of("Test2.accent"),
                "public class Test2 { public void run() { final name = \"John Doe\"; } }");
        assertTrue(new AccentCompiler().check(src2).successful());

        // 3. var name = "John Doe"; name = "Jane"; (mutable / assignable)
        SourceFile src3 = new SourceFile(
                Path.of("Test3.accent"),
                "public class Test3 { public void run() { var name = \"John Doe\"; name = \"Jane Doe\"; } }");
        assertTrue(new AccentCompiler().check(src3).successful());

        // 4. final var name = "John Doe"; name = "Jane"; -> SHOULD FAIL (immutable)
        SourceFile src4 = new SourceFile(
                Path.of("Test4.accent"),
                "public class Test4 { public void run() { final var name = \"John Doe\"; name = \"Jane Doe\"; } }");
        CompilationResult res4 = new AccentCompiler().check(src4);
        assertFalse(res4.successful());

        // 5. final name = "John Doe"; name = "Jane"; -> SHOULD FAIL (immutable)
        SourceFile src5 = new SourceFile(
                Path.of("Test5.accent"),
                "public class Test5 { public void run() { final name = \"John Doe\"; name = \"Jane Doe\"; } }");
        CompilationResult res5 = new AccentCompiler().check(src5);
        assertFalse(res5.successful());
    }

    @Test
    void rejectsWrongFileExtension() {
        CompilationResult result = new AccentCompiler().check(Path.of("Hello.java"));

        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("ACCENT-C001")));
    }
}

