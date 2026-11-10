package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NonNullMethodTest {
    @TempDir Path temp;

    private CompilationResult check(String source) {
        return new AccentCompiler().check(TestSources.split(source).entrySet().stream()
                .map(e -> new SourceFile(Path.of(e.getKey() + ".accent"), e.getValue())).toList());
    }

    private void rejects(String source, String message) {
        var result = check(source);
        assertFalse(result.successful(), source);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(message)),
                () -> result.diagnostics().toString());
    }

    private void accepts(String source) {
        var result = check(source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void declarationsAndCallsMustUseTheNameSuffix() {
        rejects("class Sample { int count?() { return 1; } }", "Primitive and void");
        rejects("class Sample { void save?() {} }", "Primitive and void");
        rejects("class Sample { String name?() { return null; } String run?() { return this.name(); } }", "Nullable result requires");
        rejects("class Sample { String name() { return \"John\"; } String run?() { return this.name()?; } }", "Non-null result");
        accepts("class Sample { static String name() { return \"John\"; } String run() { return name(); } }");
    }

    @Test void nullableReturnsAndFallthroughAreErrors() {
        rejects("class Sample { String name() { return null; } }", "must return a non-null value");
        rejects("class Sample { String name() { return; } }", "must return a non-null value");
        rejects("class Sample { String name(String input?) { return input?; } }", "must return a non-null value");
        rejects("class Sample { String name() {} }", "every completing path");
        rejects("class Sample { String name(boolean flag) { if (flag) { return \"John\"; } } }", "every completing path");
        rejects("class Sample { String name() { final action = () -> { return \"John\"; }; } }", "every completing path");
        accepts("""
                class Sample {
                    String name(String input?) {
                        if (input? != null) { return input?; }
                        throw new IllegalArgumentException("name required");
                    }
                    String either(boolean flag) {
                        if (flag) { return "John"; } else { return "Jane"; }
                    }
                    String stopped() { throw new RuntimeException("unavailable"); }
                    String attempt() {
                        try { return "John"; } catch (RuntimeException failure) { return "Jane"; }
                    }
                }
                """);
    }

    @Test void optionalCallsKeepTheWholeExpressionNullable() {
        String prefix = "class Sample { String name() { return \"John\"; } ";
        rejects(prefix + "String read(Sample customer?) { return customer?.name(); } }", "must return a non-null value");
        rejects(prefix + "void read(Sample customer?) { String name = customer?.name(); } }", "Incompatible types");
        accepts(prefix + "String read(Sample customer?) { return customer?.name() ?: \"Unknown\"; } }");
        accepts(prefix + "String read(Sample customer) { return customer.name(); } }");
    }

    @Test void overridesCannotWeakenTheInheritedContract() {
        rejects("""
                class Parent { String name() { return "parent"; } }
                class Sample extends Parent { String name?() { return null; } }
                """, "preserve the inherited non-null return contract");
        rejects("""
                interface Named { String name(); }
                class Sample implements Named { String name?() { return null; } }
                """, "preserve the inherited non-null return contract");
        accepts("""
                class Parent { String name() { return "parent"; } }
                class Sample extends Parent {
                    String name() { return super.name(); }
                    String name?(int count) { return null; }
                }
                """);
    }

    @Test void generatedAccessorsAndFactoriesExposeTheirContracts() {
        rejects("record Customer(String name?) {} class Sample { String read(Customer customer) { return customer.name(); } }", "Nullable result requires");
        accepts("model Customer(String name) {} class Sample { String read(Customer customer) { return customer.name(); } void change(Customer customer) { customer.name(\"Jane\"); } }");
        accepts("singleton class Sample { static Sample read() { return Sample.instance(); } }");
    }

    @Test void conditionalAssignmentsCannotInventANonNullGuarantee() {
        rejects("""
                class Sample {
                    String name(boolean flag) {
                        String value? = null;
                        if (flag) { value? = "John"; }
                        return value?;
                    }
                }
                """, "must return a non-null value");
        rejects("""
                class Sample {
                    String name(boolean flag) {
                        String value? = "John";
                        if (value? != null) { value? = null; }
                        return value?;
                    }
                }
                """, "must return a non-null value");
        rejects("""
                class Sample {
                    String name(boolean flag) {
                        do { if (flag) { continue; } return "John"; } while (false);
                    }
                }
                """, "every completing path");
        accepts("""
                class Sample {
                    String name(boolean flag) {
                        String value? = null;
                        if (flag) { value? = "John"; } else { value? = "Jane"; }
                        return value?;
                    }
                }
                """);
    }

    @Test void compiledLibrariesRetainMarkedMethodAndAccessorContracts() throws Exception {
        Path library = Files.createDirectories(temp.resolve("library"));
        Files.writeString(library.resolve("Customer.accent"), """
                record Customer(String name) { String label() { return this.name; } }
                """);
        Path classes = temp.resolve("library-classes");
        var libraryResult = new AccentCompiler().compile(List.of(library), classes,
                List.of("build/classes/java/main"), temp.resolve("library-generated"), true);
        assertTrue(libraryResult.successful(), () -> libraryResult.diagnostics().toString());
        Path consumer = temp.resolve("Consumer.accent");
        Files.writeString(consumer, """
                class Consumer {
                    static String run() {
                        final customer = new Customer("John");
                        return customer.label() + customer.name() + customer.toString();
                    }
                }
                """);
        List<String> classpath = List.of("build/classes/java/main", classes.toString());
        var result = new AccentCompiler().compile(List.of(consumer), temp.resolve("consumer-classes"),
                classpath, temp.resolve("consumer-generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        Files.writeString(consumer, "class Consumer { String run(Customer customer) { return customer.label()?; } }");
        var invalid = new AccentCompiler().compile(List.of(consumer), temp.resolve("invalid-classes"),
                classpath, temp.resolve("invalid-generated"), true);
        assertFalse(invalid.successful());
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.message().contains("Non-null result")),
                () -> invalid.diagnostics().toString());
    }

    @Test void documentedMethodExamplesCompile() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String section = readme.split("#### Non-null method results", 2)[1].split("### 2.", 2)[0];
        var snippets = java.util.regex.Pattern.compile("```accent\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(section);
        StringBuilder source = new StringBuilder();
        while (snippets.find()) source.append(snippets.group(1)).append("\n");
        assertFalse(source.isEmpty());
        accepts(source.toString());
    }

    @Test void executesDispatchOptionalCallsAndReturnGuardsWithoutChangingJavaNames() throws Exception {
        Files.writeString(temp.resolve("Parent.accent"), """
                class Parent {
                    protected String _label() { return "parent"; }
                    String name() { return "John"; }
                }
                """);
        Files.writeString(temp.resolve("Sample.accent"), """
                class Sample extends Parent {
                    String value = "Jane";
                    String name() { return this.value; }
                    String label() { return super._label(); }
                    static String read(Parent customer) { return customer.name(); }
                    static String optional(Parent customer?) { return customer?.name() ?: "Unknown"; }
                    static java.util.function.Supplier<String> supplier() {
                        java.util.function.Supplier<String> result = () -> { return null; };
                        return result;
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(temp), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            var parent = loader.loadClass("Parent");
            var sample = loader.loadClass("Sample");
            var value = sample.getConstructor().newInstance();
            assertEquals("Jane", sample.getMethod("read", parent).invoke(null, value));
            assertEquals("parent", sample.getMethod("label").invoke(value));
            assertEquals("Unknown", sample.getMethod("optional", parent).invoke(null, (Object) null));
            assertEquals("Jane", sample.getMethod("optional", parent).invoke(null, value));
            var supplier = (java.util.function.Supplier<?>) sample.getMethod("supplier").invoke(null);
            assertNull(supplier.get(), "The enclosing method's contract must not affect lambda returns");
            sample.getField("value").set(value, null); // Simulate a Java caller bypassing Accent field checks.
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> sample.getMethod("name").invoke(value));
            assertInstanceOf(NullPointerException.class, failure.getCause());
        }
    }
}
