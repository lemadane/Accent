package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NonNullBindingTest {
    @TempDir Path temp;

    private CompilationResult check(String body) {
        return new AccentCompiler().check(new SourceFile(Path.of("Sample.accent"), body));
    }

    private void rejects(String body, String message) {
        var result = check(body);
        assertFalse(result.successful(), body);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(message)),
                () -> result.diagnostics().toString());
    }

    @Test void requiresInitializersAndRejectsNullAssignments() {
        rejects("class Sample { void run() { String name!; } }", "requires an initializer");
        rejects("class Sample { String name!; }", "requires an initializer");
        rejects("class Sample { void run() { String name! = null; } }", "Incompatible types");
        rejects("class Sample { String name! = null; }", "Incompatible types");
        rejects("class Sample { void run() { var name! = null; } }", "Incompatible types");
        rejects("class Sample { void run() { String name! = \"ok\"; name! = null; } }", "Incompatible types");
        rejects("class Sample { String! name = \"ok\"; }", "after the binding name");
        rejects("class Sample { void run(String! name) {} }", "after the binding name");
        rejects("class Sample { void run() { int count! = 1; } }", "Primitive bindings");
        rejects("class Sample { final count! = 1; }", "Primitive bindings");
        rejects("record Sample(String name! = null) {}", "Incompatible types");
        rejects("class Sample { void run(String name! = null) {} }", "Incompatible types");
    }

    @Test void markerIsRequiredAndCannotAssertNullableBindings() {
        rejects("class Sample { void run(String name!) { System.out.println(name); } }", "every use");
        rejects("class Sample { void run(String name) { System.out.println(name!); } }", "not declared");
        rejects("class Sample { String name! = \"ok\"; String run() { return this.name; } }", "every use");
        rejects("class Sample { void run() { final name! = \"ok\"; System.out.println(name); } }", "every use");
        var result = check("""
                class Sample {
                    void run(String name) {
                        if (name != null) { System.out.println(name); }
                    }
                }
                """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void javaCallersCannotSupplyNullToMarkedParametersOrComponents() throws Exception {
        Files.writeString(temp.resolve("Required.accent"), "record Required(String name!) {}");
        Files.writeString(temp.resolve("Mutable.accent"), "model Mutable(String name!) {}");
        Files.writeString(temp.resolve("Sample.accent"), """
                class Sample {
                    static String echo(String name!) { return name!; }
                    static java.util.function.Function<String, String> function() {
                        java.util.function.Function<String, String> greet! = (name!) -> $"Hi {name!}";
                        return greet!;
                    }
                    static void loop() {
                        final values! = new java.util.ArrayList<String>();
                        values!.add(null);
                        for (String value! : values!) { System.out.println(value!); }
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(temp), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            for (String name : List.of("Required", "Mutable")) {
                var constructor = loader.loadClass(name).getConstructor(String.class);
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> constructor.newInstance((Object) null));
                assertInstanceOf(NullPointerException.class, failure.getCause());
            }
            var mutable = loader.loadClass("Mutable");
            var value = mutable.getConstructor(String.class).newInstance("John");
            var setterFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> mutable.getMethod("name", String.class).invoke(value, (Object) null));
            assertInstanceOf(NullPointerException.class, setterFailure.getCause());
            var method = loader.loadClass("Sample").getMethod("echo", String.class);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(null, (Object) null));
            assertInstanceOf(NullPointerException.class, failure.getCause());
            @SuppressWarnings("unchecked")
            var function = (java.util.function.Function<String, String>)
                    loader.loadClass("Sample").getMethod("function").invoke(null);
            assertEquals("Hi John", function.apply("John"));
            assertThrows(NullPointerException.class, () -> function.apply(null));
            var loopFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> loader.loadClass("Sample").getMethod("loop").invoke(null));
            assertInstanceOf(NullPointerException.class, loopFailure.getCause());
        }
    }

    @Test void executesInterpolationTruthinessAndInheritedFieldAccess() throws Exception {
        Files.writeString(temp.resolve("Parent.accent"), """
                class Parent { protected String _customer! = "John"; }
                """);
        Files.writeString(temp.resolve("Sample.accent"), """
                class Sample extends Parent {
                    private String _name! = "Doe";
                    String message() { return $"{super._customer!} {this._name!}"; }
                    static String run() {
                        java.util.List<String> myList! = new java.util.ArrayList<String>();
                        if (myList!) { throw new RuntimeException("empty list was truthy"); }
                        if (not myList!) { myList!.add("item"); }
                        if (not myList!) { throw new RuntimeException("populated list was falsy"); }
                        String[] names! = new String[0];
                        if (names!) { throw new RuntimeException("empty array was truthy"); }
                        final sample! = new Sample();
                        return sample!.message();
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(temp), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            assertEquals("John Doe", loader.loadClass("Sample").getMethod("run").invoke(null));
        }
    }
}
