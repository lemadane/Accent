package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaGenerationTest {
    @TempDir Path root;

    @Test void platformGenerationDoesNotInvokeHostJavac() throws Exception {
        Path source = root.resolve("Screen.accent");
        Files.writeString(source, "package demo import android.app.Activity class Screen extends Activity {}");
        Path output = root.resolve("generated");
        var result = new AccentCompiler().generateJava(List.of(source), List.of(), output);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(Files.exists(output.resolve("demo/Screen.java")));
        assertFalse(Files.exists(output.resolve("demo/Screen.class")));
    }

    @Test void semanticErrorsStillPreventGeneration() throws Exception {
        Path source = root.resolve("Broken.accent");
        Files.writeString(source, "class Broken { static String value() { return null } }");
        Path output = root.resolve("generated");
        var result = new AccentCompiler().generateJava(List.of(source), List.of(), output);
        assertFalse(result.successful());
        assertFalse(Files.exists(output.resolve("Broken.java")));
    }
    @Test void qualifiedEnumConstantsCompile() throws Exception {
        Path source = root.resolve("State.accent");
        Files.writeString(source, "public enum State { ACTIVE, EXPIRED public boolean blocked() { return this == static.EXPIRED } }");
        var result = new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] { root.resolve("classes").toUri().toURL() })) {
            Class<?> state = loader.loadClass("State");
            assertEquals(false, state.getMethod("blocked").invoke(state.getField("ACTIVE").get(null)));
            assertEquals(true, state.getMethod("blocked").invoke(state.getField("EXPIRED").get(null)));
        }
    }
}
