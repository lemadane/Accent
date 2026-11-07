package accent.compiler;

import accent.diagnostic.Diagnostic;
import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Name-based visibility: {@code x} → public, {@code _x} → package-private, {@code __x} → protected. */
class VisibilityTest {

    @TempDir
    Path tempDir;

    private static final String COUNTER = """
            package vis;
            class Counter {
                int total = 0;
                int _cache = 0;
                int __step = 1;
                static int shared = 0;
                void inc() { this.total = this.total + this.__step; }
                void _reset() { this.total = 0; }
                void __grow() { this.__step++; }
                static int _helper() { return 1; }
            }
            """;

    private Path write(String relative, String content) throws Exception {
        Path file = tempDir.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private CompilationResult compileAll() {
        return new AccentCompiler().compile(List.of(tempDir.resolve("src")), tempDir.resolve("classes"),
                List.of("build/classes/java/main"), tempDir.resolve("generated"), true);
    }

    private static CompilationResult check(String fileName, String source) {
        return new AccentCompiler().check(new SourceFile(Path.of(fileName), source));
    }

    private static void assertError(CompilationResult result, String fragment) {
        assertFalse(result.successful(), () -> "Expected failure containing: " + fragment);
        assertTrue(result.diagnostics().stream().map(Diagnostic::message).anyMatch(m -> m.contains(fragment)),
                () -> "Expected diagnostic containing '" + fragment + "' but got " + result.diagnostics());
    }

    @Test
    void memberModifiersAreDerivedFromNames() throws Exception {
        write("vis/Counter.accent", COUNTER);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());

        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()})) {
            Class<?> type = loader.loadClass("vis.Counter");
            assertTrue(Modifier.isPublic(type.getModifiers()));

            assertVisibility(type.getDeclaredField("total").getModifiers(), "public");
            assertVisibility(type.getDeclaredField("_cache").getModifiers(), "package");
            assertVisibility(type.getDeclaredField("__step").getModifiers(), "protected");
            Field shared = type.getDeclaredField("shared");
            assertVisibility(shared.getModifiers(), "public");
            assertTrue(Modifier.isStatic(shared.getModifiers()));

            assertVisibility(type.getDeclaredMethod("inc").getModifiers(), "public");
            assertVisibility(type.getDeclaredMethod("_reset").getModifiers(), "package");
            assertVisibility(type.getDeclaredMethod("__grow").getModifiers(), "protected");
            Method helper = type.getDeclaredMethod("_helper");
            assertVisibility(helper.getModifiers(), "package");
            assertTrue(Modifier.isStatic(helper.getModifiers()));

            for (Constructor<?> c : type.getDeclaredConstructors()) {
                assertVisibility(c.getModifiers(), "public");
            }
        }
    }

    @Test
    void declaredConstructorsArePublic() throws Exception {
        write("vis/Point.accent", """
                package vis;
                class Point {
                    int _x;
                    Point(int x) { this._x = x; }
                    int x() { return this._x; }
                }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()})) {
            Class<?> type = loader.loadClass("vis.Point");
            assertVisibility(type.getDeclaredConstructor(int.class).getModifiers(), "public");
            assertVisibility(type.getDeclaredField("_x").getModifiers(), "package");
        }
    }

    @Test
    void protectedMembersAreVisibleToSubclassesInOtherPackages() throws Exception {
        write("vis/Counter.accent", COUNTER);
        write("other/FastCounter.accent", """
                package other;
                import vis.Counter;
                class FastCounter extends Counter {
                    void boost() { this.__step = 10; this.__grow(); this.inc(); }
                }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void packagePrivateMembersAreHiddenFromOtherPackages() throws Exception {
        write("vis/Counter.accent", COUNTER);
        write("other/Snooper.accent", """
                package other;
                import vis.Counter;
                class Snooper {
                    int peek(Counter! counter) { return counter._cache; }
                }
                """);
        CompilationResult result = compileAll();
        assertFalse(result.successful(), "package-private member must not be accessible from another package");
    }

    @Test
    void packagePrivateMembersAreVisibleInSamePackage() throws Exception {
        write("vis/Counter.accent", COUNTER);
        write("vis/Neighbor.accent", """
                package vis;
                class Neighbor {
                    int peek(Counter! counter) { counter._reset(); return counter._cache; }
                }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void privateKeywordIsRejectedWithHint() {
        assertError(check("A.accent", "class A { private int count = 0; }"),
                "'private' was removed; rename 'count' to '_count' for package-private");
    }

    @Test
    void publicKeywordIsRejectedWithHint() {
        assertError(check("A.accent", "class A { public void run() {} }"),
                "'public' was removed; members are public by default");
        assertError(check("A.accent", "public class A {}"), "'public' was removed; types are always public");
    }

    @Test
    void protectedKeywordIsRejectedWithHint() {
        assertError(check("A.accent", "class A { protected int x = 0; }"),
                "'protected' was removed; rename 'x' to '__x'");
    }

    @Test
    void acceptsInternalAsAnOrdinaryIdentifier() {
        CompilationResult result = check("A.accent", "class A { int run() { var internal = 1; return internal; } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void onlyOneTopLevelTypePerFile() {
        assertError(check("Shapes.accent", "class Shapes {}\nclass Circle {}"), "move 'Circle' to Circle.accent");
    }

    @Test
    void extensionsMayAccompanyTheTopLevelType() {
        CompilationResult result = check("Text.accent",
                "class Text {}\nextension String { String shout() { return this.toUpperCase(); } }");
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains("one top-level type")),
                () -> result.diagnostics().toString());
    }

    @Test
    void prefixedTypeNamesAreRejected() {
        assertError(check("_Hidden.accent", "class _Hidden {}"), "Type names cannot start with '_'");
        assertError(check("__Hidden.accent", "record __Hidden(int x) {}"), "Type names cannot start with '_'");
    }

    @Test
    void prefixedInterfaceMembersAreRejected() {
        assertError(check("Shape.accent", "interface Shape { double _area(); }"),
                "Interface members are always public");
        CompilationResult ok = check("Shape.accent", "interface Shape { double area(); }");
        assertTrue(ok.successful(), () -> ok.diagnostics().toString());
    }

    @Test
    void bareUnderscoreNamesAreRejected() {
        assertError(check("A.accent", "class A { int _ = 0; }"), "is not a valid member name");
        assertError(check("A.accent", "class A { void __() {} }"), "is not a valid member name");
    }

    @Test
    void underscoreParametersAndLocalsAreUnaffected() {
        CompilationResult result = check("A.accent",
                "class A { int run(int _unused) { var _tmp = 1; return _tmp; } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void singletonsCannotDeclareConstructors() {
        assertError(check("Config.accent", "singleton class Config { Config() {} }"),
                "Singletons cannot declare constructors");
    }

    private static void assertVisibility(int modifiers, String expected) {
        String actual = Modifier.isPublic(modifiers) ? "public"
                : Modifier.isProtected(modifiers) ? "protected"
                : Modifier.isPrivate(modifiers) ? "private" : "package";
        assertEquals(expected, actual);
    }
}
