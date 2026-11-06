package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NullCoalescingCompatibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesObjectValuesAndUsesFallbackOnlyForNull() throws Exception {
        Path source = tempDir.resolve("Coalesce.accent");
        Files.writeString(source, """
            public class Coalesce {
                public static Object value(Object x) {
                    return x ?? "fallback";
                }
            }
            """);
        var result = new AccentCompiler().compile(List.of(source), tempDir, List.of(), tempDir, true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()})) {
            var method = loader.loadClass("Coalesce").getMethod("value", Object.class);
            Object value = new Object();
            assertSame(value, method.invoke(null, value));
            assertEquals("fallback", method.invoke(null, new Object[]{null}));
        }
    }
}
