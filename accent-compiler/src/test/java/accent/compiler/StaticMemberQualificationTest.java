package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StaticMemberQualificationTest {
    @TempDir Path temp;

    @Test void staticReceiverWorksInMethodsInitializersAndLambdasAndJavaCallers() throws Exception {
        Path source = temp.resolve("Counter.accent");
        Files.writeString(source, """
                package example;
                class Counter {
                    static int count = 1;
                    static int initial = static.count;
                    Counter() { static.increment(); }
                    static void increment() { static.count++; }
                    static int read() { return static.count; }
                    int run() {
                        static.increment();
                        Runnable action = () -> static.increment();
                        action.run();
                        return static.read() + static.initial;
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        String generated = Files.readString(temp.resolve("java/example/Counter.java"));
        assertTrue(generated.contains("example.Counter.increment()"));
        assertFalse(generated.contains("static.increment()"));
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            Class<?> counter = loader.loadClass("example.Counter");
            assertEquals(5, counter.getMethod("run").invoke(counter.getConstructor().newInstance()));
            counter.getMethod("increment").invoke(null);
            assertEquals(5, counter.getMethod("read").invoke(null));
        }
    }

    @Test void rejectsEveryOtherReceiverForOwnStaticMembers() {
        for (String expression : List.of("increment()", "Counter.increment()", "this.increment()",
                "count", "Counter.count", "this.count", "other.increment()", "other.count")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Counter.accent"),
                    "class Counter { static int count = 0; static int increment() { return 1; }"
                    + " void run(Counter other) { final value = " + expression + "; } }"));
            assertFalse(result.successful(), expression);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("must be accessed with 'static.")),
                    result.diagnostics().toString());
        }
    }

    @Test void rejectsInstanceMembersAndStandaloneStaticReceiver() {
        for (String expression : List.of("static.value", "static.read()", "static", "static?.read()", "static.class")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Counter.accent"),
                    "class Counter { int value; int read() { return this.value; }"
                    + " void run() { final result = " + expression + "; } }"));
            assertFalse(result.successful(), expression);
        }
    }

    @Test void externalAndInheritedStaticsUseDeclaringType() {
        SourceFile parent = new SourceFile(Path.of("Parent.accent"),
                "class Parent { public static int count = 1; public static int read() { return static.count; } }");
        for (String expression : List.of("Parent.count", "Parent.read()")) {
            var result = new AccentCompiler().check(List.of(parent, new SourceFile(Path.of("Child.accent"),
                    "class Child extends Parent { int run() { return " + expression + "; } }")));
            assertTrue(result.successful(), result.diagnostics().toString());
        }
        for (String expression : List.of("static.count", "static.read()", "super.read()", "this.count", "read()", "parent.read()", "Child.read()")) {
            var result = new AccentCompiler().check(List.of(parent, new SourceFile(Path.of("Child.accent"),
                    "class Child extends Parent { int run(Parent parent) { return " + expression + "; } }")));
            assertFalse(result.successful(), expression);
        }
    }
}
