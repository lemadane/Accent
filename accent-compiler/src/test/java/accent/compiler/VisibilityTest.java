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

/** Explicit visibility with required underscore prefixes for non-public members. */
class VisibilityTest {

    @TempDir
    Path tempDir;

    private static final String COUNTER = """
            package vis;
            class Counter {
                int total = 0;
                private int _cache = 0;
                protected int __step = 1;
                static int shared = 0;
                void inc() { this.total = this.total + this.__step; }
                private void _reset() { this.total = 0; }
                protected void __grow() { this.__step++; }
                private static int _helper() { return 1; }
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
    void memberModifiersComeFromExplicitVisibility() throws Exception {
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
                    private int _x;
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
                    void boost() { super.__step = 10; super.__grow(); super.inc(); }
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
                    int peek(Counter counter) { return counter._cache; }
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
                    int peek(Counter counter) { counter._reset(); return counter._cache; }
                }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void explicitModifiersDetermineAccess() throws Exception {
        write("vis/Explicit.accent", """
                package vis;
                public class Explicit {
                    public Explicit() {}
                    public int _visible = 1;
                    private int _hidden = 2;
                    private static final int __shared = 3;
                    public int _read() { return this._hidden; }
                    private int _readHidden() { return this._hidden; }
                }
                """);
        write("vis/Neighbor.accent", """
                package vis;
                class Neighbor {
                    int read(Explicit value) { return value._hidden + value._readHidden(); }
                }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()})) {
            Class<?> type = loader.loadClass("vis.Explicit");
            assertVisibility(type.getDeclaredField("_visible").getModifiers(), "public");
            assertVisibility(type.getDeclaredField("_hidden").getModifiers(), "package");
            assertVisibility(type.getDeclaredField("__shared").getModifiers(), "package");
            assertVisibility(type.getDeclaredMethod("_read").getModifiers(), "public");
            assertVisibility(type.getDeclaredMethod("_readHidden").getModifiers(), "package");
        }
    }

    @Test
    void explicitProtectedAllowsPackageAndSubclassAccess() throws Exception {
        write("vis/Base.accent", """
                package vis;
                class Base {
                    protected int _value = 2;
                    protected int _read() { return this._value; }
                }
                """);
        write("vis/Neighbor.accent", """
                package vis;
                class Neighbor { int read(Base base) { return base._value + base._read(); } }
                """);
        write("other/Child.accent", """
                package other;
                import vis.Base;
                class Child extends Base { int total() { return super._value + super._read(); } }
                """);
        CompilationResult result = compileAll();
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()})) {
            Class<?> base = loader.loadClass("vis.Base");
            assertVisibility(base.getDeclaredField("_value").getModifiers(), "protected");
            assertVisibility(base.getDeclaredMethod("_read").getModifiers(), "protected");
            Class<?> child = loader.loadClass("other.Child");
            assertEquals(4, child.getMethod("total").invoke(child.getConstructor().newInstance()));
        }
    }

    @Test
    void explicitPrivateIsHiddenFromOtherPackages() throws Exception {
        write("vis/Secret.accent", "package vis; class Secret { private int _value = 1; }");
        write("other/Reader.accent", """
                package other;
                import vis.Secret;
                class Reader { int read(Secret secret) { return secret._value; } }
                """);
        assertFalse(compileAll().successful());
    }

    @Test
    void conflictingVisibilityModifiersAreRejected() {
        assertError(check("A.accent", "class A { public private int value; }"),
                "Only one visibility modifier is allowed");
        assertError(check("A.accent", "public public class A {}"),
                "Only one visibility modifier is allowed");
    }

    @Test
    void interfaceMembersMustRemainPublic() {
        assertTrue(check("A.accent", "public interface A { public int value(); }").successful());
        assertError(check("A.accent", "interface A { private int value(); }"),
                "Interface members are always public");
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
    void underscoresAloneDoNotChangeDefaultPublicVisibility() throws Exception {
        write("vis/Names.accent", "package vis; class Names { int _one; int __two; void _run() {} }");
        assertTrue(compileAll().successful());
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()})) {
            Class<?> names = loader.loadClass("vis.Names");
            assertVisibility(names.getDeclaredField("_one").getModifiers(), "public");
            assertVisibility(names.getDeclaredField("__two").getModifiers(), "public");
            assertVisibility(names.getDeclaredMethod("_run").getModifiers(), "public");
        }
        assertTrue(check("Shape.accent", "interface Shape { double _area(); }").successful());
    }

    @Test
    void nonPublicDeclarationsRequireLeadingUnderscores() {
        for (String visibility : List.of("private", "protected")) {
            for (String member : List.of("int count;", "static int count;", "final int count = 1;",
                    "void count() {}", "static void count() {}", "int count_ = 1;")) {
                assertError(check("A.accent", "class A { " + visibility + " " + member + " }"), "must start with '_'");
            }
            assertError(check("Extensions.accent", "extension String { " + visibility
                    + " int count() { return 1; } }"), "must start with '_'");
            assertTrue(check("A.accent", "class A { " + visibility + " int _count; "
                    + visibility + " void _run() {} }").successful());
        }
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
