package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionVisibilityTest {
    @TempDir
    Path tempDir;

    private void write(String relative, String source) throws Exception {
        Path file = tempDir.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private void compile() {
        CompilationResult result = new AccentCompiler().compile(List.of(tempDir.resolve("src")),
                tempDir.resolve("classes"), List.of("build/classes/java/main"), tempDir.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    private URLClassLoader loader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()});
    }

    @Test
    void accompanyingExtensionsExportAcrossPackagesInSeparateJavaFiles() throws Exception {
        write("vis/Text.accent", """
                package vis;
                class Text {}
                extension String {
                    String decorate() { return "<" + this + ">"; }
                }
                extension String {
                    int extra() { return 4; }
                }
                extension StringBuilder {
                    int size() { return this.length(); }
                }
                """);
        write("other/Caller.accent", """
                package other;
                import vis.Text;
                class Caller {
                    static String run() { return "x".decorate() + (new StringBuilder("abc").size() + "x".extra()); }
                }
                """);

        compile();

        assertTrue(Files.isRegularFile(tempDir.resolve("generated/vis/Text.java")));
        assertTrue(Files.isRegularFile(tempDir.resolve("generated/vis/Extension_String.java")));
        assertTrue(Files.isRegularFile(tempDir.resolve("generated/vis/Extension_StringBuilder.java")));
        try (var loader = loader()) {
            assertEquals("<x>7", loader.loadClass("other.Caller").getMethod("run").invoke(null));
            assertTrue(Modifier.isPublic(loader.loadClass("vis.Extension_String").getModifiers()));
        }
    }

    @Test
    void extensionOnlyFilesCanExportMultipleHelpers() throws Exception {
        write("vis/Extensions.accent", """
                package vis;
                extension String { int size() { return this.length(); } }
                extension StringBuilder { int size() { return this.length(); } }
                """);
        write("other/Caller.accent", """
                package other;
                class Caller {
                    static int run() { return "abc".size() + new StringBuilder("de").size(); }
                }
                """);

        compile();

        try (var loader = loader()) {
            assertEquals(5, loader.loadClass("other.Caller").getMethod("run").invoke(null));
        }
    }

    @Test
    void extensionMethodsKeepExplicitVisibilityAndAnnotations() throws Exception {
        write("vis/Extensions.accent", """
                package vis;
                extension String {
                    @Deprecated int visible() { return 1; }
                    private static int _hidden() { return 2; }
                    protected int __protected() { return 3; }
                }
                """);
        write("vis/Caller.accent", """
                package vis;
                class Caller {
                    static int run() { return "x"._hidden() + "x".__protected(); }
                }
                """);

        compile();

        try (var loader = loader()) {
            Class<?> helper = loader.loadClass("vis.Extension_String");
            var visible = helper.getDeclaredMethod("visible", String.class);
            var hidden = helper.getDeclaredMethod("_hidden", String.class);
            var protectedMethod = helper.getDeclaredMethod("__protected", String.class);
            assertTrue(Modifier.isPublic(visible.getModifiers()));
            assertTrue(visible.isAnnotationPresent(Deprecated.class));
            assertFalse(Modifier.isPublic(hidden.getModifiers()));
            assertFalse(Modifier.isProtected(hidden.getModifiers()));
            assertFalse(Modifier.isPrivate(hidden.getModifiers()));
            assertTrue(Modifier.isProtected(protectedMethod.getModifiers()));
            assertTrue(Modifier.isStatic(visible.getModifiers()));
            assertTrue(Modifier.isStatic(hidden.getModifiers()));
            assertTrue(Modifier.isStatic(protectedMethod.getModifiers()));
            assertEquals(5, loader.loadClass("vis.Caller").getMethod("run").invoke(null));
        }
    }
}
