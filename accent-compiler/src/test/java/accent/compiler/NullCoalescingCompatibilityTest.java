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
            class Coalesce {
                Integer port? = null;
                static int reads = 0;
                static int fallbacks = 0;
                static Object source?() { static.reads++; return null; }
                static Integer port() {
                    final config = new Coalesce();
                    Integer port = config.port? else 8080;
                    return port;
                }
                static Object once() { return static.source()? else static.fallback(); }
                static String nested(boolean active, String name?) {
                    return active then (name? else "Guest") else "Inactive";
                }
                static String chain(String first?, String second?) { return first? else second? else "last"; }
                static String branch(boolean active, String name?) {
                    if (active) { return name? else "Guest"; } else { return "Inactive"; }
                }
                static String fallback() { static.fallbacks++; return "fallback"; }
                static Object value(Object x?) {
                    return x? else static.fallback();
                }
            }
            """);
        var result = new AccentCompiler().compile(List.of(source), tempDir, List.of(), tempDir, true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()})) {
            var method = loader.loadClass("Coalesce").getMethod("value", Object.class);
            Object value = new Object();
            assertSame(value, method.invoke(null, value));
            assertEquals("", method.invoke(null, ""));
            assertEquals(false, method.invoke(null, false));
            assertEquals(0, method.invoke(null, 0));
            assertEquals(0, loader.loadClass("Coalesce").getField("fallbacks").getInt(null));
            assertEquals("fallback", method.invoke(null, new Object[]{null}));
            assertEquals(1, loader.loadClass("Coalesce").getField("fallbacks").getInt(null));
            Class<?> type = loader.loadClass("Coalesce");
            assertEquals(8080, type.getMethod("port").invoke(null));
            assertEquals("fallback", type.getMethod("once").invoke(null));
            assertEquals(1, type.getField("reads").getInt(null));
            assertEquals(2, type.getField("fallbacks").getInt(null));
            assertEquals("Guest", type.getMethod("nested", boolean.class, String.class).invoke(null, true, null));
            assertEquals("Inactive", type.getMethod("nested", boolean.class, String.class).invoke(null, false, null));
            assertEquals("Name", type.getMethod("nested", boolean.class, String.class).invoke(null, true, "Name"));
            assertEquals("last", type.getMethod("chain", String.class, String.class).invoke(null, null, null));
            assertEquals("second", type.getMethod("chain", String.class, String.class).invoke(null, null, "second"));
            assertEquals("", type.getMethod("chain", String.class, String.class).invoke(null, "", "second"));
            assertEquals("Guest", type.getMethod("branch", boolean.class, String.class).invoke(null, true, null));
            assertEquals("Inactive", type.getMethod("branch", boolean.class, String.class).invoke(null, false, null));
        }
    }
    @Test void rejectsLegacyAndMissingFallbacks() {
        for (String expression : List.of("name? ?: \"Guest\"", "name? else")) {
            var result = new AccentCompiler().check(new accent.source.SourceFile(Path.of("Bad.accent"),
                    "class Bad { String value(String name?) { return " + expression + "; } }"));
            assertFalse(result.successful(), expression);
        }
    }

}
