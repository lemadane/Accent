package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InheritedMemberQualificationTest {
    @TempDir Path directory;

    private static final String PARENT = """
            class Parent {
                public int count = 1;
                protected int _step = 2;
                private int _cache = 3;
                public static int shared = 4;
                public String label() { return "parent"; }
                protected int _read() { return this._step; }
                public int overload(int value) { return value; }
            }
            """;

    private static SourceFile source(String name, String text) {
        return new SourceFile(Path.of(name + ".accent"), text);
    }

    private static CompilationResult checkChild(String text) {
        return new AccentCompiler().check(List.of(source("Parent", PARENT), source("Child", text)));
    }

    @Test
    void inheritedInstanceAccessRequiresSuperIncludingMatchingOverloads() {
        for (String expression : List.of("this.count", "this._step", "this._cache", "this._read()",
                "this.label()", "this.overload(1)", "(this).count")) {
            var result = checkChild("class Child extends Parent { int overload(String text) { return 0; }"
                    + " void inspect() { final value = " + expression + "; } }");
            assertFalse(result.successful(), expression);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("must be accessed with 'super.")),
                    () -> expression + ": " + result.diagnostics());
        }
    }

    @Test
    void unqualifiedInheritedMembersSuggestSuper() {
        for (String expression : List.of("count", "_step", "_read()", "label()")) {
            var result = checkChild("class Child extends Parent { void inspect() { final value = " + expression + "; } }");
            assertFalse(result.successful(), expression);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("qualified with 'super.'")),
                    () -> result.diagnostics().toString());
        }
    }

    @Test
    void ownOverridesAndShadowedFieldsUseThisWhileSuperKeepsParentDispatch() throws Exception {
        List<SourceFile> sources = List.of(source("Parent", PARENT), source("Child", """
                class Child extends Parent {
                    public int count = 10;
                    private int _own = 20;
                    public String label() { return "child"; }
                    public String compare() {
                        return this.label() + "|" + super.label() + "|"
                                + this.count + "|" + super.count + "|" + super._read()
                                + "|" + super._cache + "|" + this._own + "|" + Parent.shared;
                    }
                    public String other(Parent! parent) { return parent.label(); }
                }
                """), source("Grandchild", """
                class Grandchild extends Child {
                    public String label() { return "grandchild"; }
                }
                """));
        var paths = new ArrayList<Path>();
        for (SourceFile source : sources) {
            Path file = directory.resolve(source.path().getFileName());
            Files.writeString(file, source.content());
            paths.add(file);
        }
        var result = new AccentCompiler().compile(paths, directory.resolve("classes"),
                List.of("build/classes/java/main"), directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()})) {
            Class<?> child = loader.loadClass("Child");
            Class<?> grandchild = loader.loadClass("Grandchild");
            Object value = grandchild.getConstructor().newInstance();
            assertEquals("grandchild|parent|10|1|2|3|20|4", child.getMethod("compare").invoke(value));
            assertEquals("grandchild", child.getMethod("other", loader.loadClass("Parent")).invoke(value, value));
        }
    }

    @Test
    void implicitObjectParentAndImportedJavaMembersSupportSuper() {
        var object = new AccentCompiler().check(source("Plain", """
                class Plain { String describe() { return super.toString(); } }
                """));
        assertTrue(object.successful(), () -> object.diagnostics().toString());
        var java = new AccentCompiler().check(source("JavaChild", """
                class JavaChild extends java.util.ArrayList {
                    int inspect() { super.removeRange(0, 0); return super.modCount + super.size(); }
                }
                """));
        assertTrue(java.successful(), () -> java.diagnostics().toString());
    }

    @Test
    void abstractParentMethodsCannotBeInvokedThroughSuper() {
        var result = new AccentCompiler().check(source("Child", """
                class Child extends java.util.AbstractList {
                    int inspect() { return super.size(); }
                }
                """));
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("abstract parent method 'size'")),
                () -> result.diagnostics().toString());
    }

    @Test
    void documentedInheritanceDemoCompilesAndExecutes() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String readme = Files.readString(root.resolve("README.md"));
        Path demo = root.resolve("accent-compiler/src/e2e/examples/inheritance");
        for (String name : List.of("Counter", "FastCounter")) {
            String section = readme.split("\\*\\*" + name + "[.]accent\\*\\*", 2)[1];
            String example = section.split("```accent\\n", 2)[1].split("```", 2)[0];
            assertEquals(Files.readString(demo.resolve(name + ".accent")).strip(), example.strip());
        }
        var result = new AccentCompiler().compile(List.of(demo), directory.resolve("classes"),
                List.of("build/classes/java/main"), directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()})) {
            Class<?> type = loader.loadClass("FastCounter");
            Object instance = type.getConstructor().newInstance();
            type.getMethod("increment").invoke(instance);
            assertEquals(3, type.getField("total").get(instance));
            assertEquals("fast / counter", type.getMethod("summary").invoke(instance));
        }
    }

    @Test
    void extensionsKeepThisAsTheirTargetReceiver() {
        var result = new AccentCompiler().check(source("Extensions", """
                extension StringBuilder! {
                    private int _length() { return this.length(); }
                    public String description() { return this.toString(); }
                }
                """));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
}
