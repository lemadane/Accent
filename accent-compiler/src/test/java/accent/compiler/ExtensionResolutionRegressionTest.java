package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionResolutionRegressionTest {
    @TempDir
    Path tempDir;

    private void write(String relative, String source) throws Exception {
        Path file = tempDir.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private CompilationResult check() throws Exception {
        try (var files = Files.walk(tempDir.resolve("src"))) {
            List<SourceFile> sources = files.filter(Files::isRegularFile).map(path -> {
                try {
                    return new SourceFile(path, Files.readString(path));
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).toList();
            return new AccentCompiler().check(sources);
        }
    }

    private URLClassLoader compile() throws Exception {
        CompilationResult result = new AccentCompiler().compile(List.of(tempDir.resolve("src")),
                tempDir.resolve("classes"), List.of("build/classes/java/main"), tempDir.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        return new URLClassLoader(new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()});
    }

    private void importedExtension() throws Exception {
        write("domain/Widget.accent", """
                package domain;
                class Widget {
                    String! name;
                    Widget(String! name) { this.name = name; }
                }
                """);
        write("domain/Label.accent", """
                package domain;
                class Label {
                    String! text;
                    Label(String! text) { this.text = text; }
                }
                """);
        write("extensions/WidgetExtensions.accent", """
                package extensions;
                import domain.Widget;
                import domain.Label;
                extension Widget! {
                    Label! label(Label! prefix) { return new Label(prefix.text + this.name); }
                }
                """);
    }

    @Test
    void importedExtensionTypesResolveInTheirDeclarationScope() throws Exception {
        importedExtension();
        write("app/Caller.accent", """
                package app;
                class Caller {
                    static String! run() {
                        return new domain.Widget("x").label(new domain.Label("id=")).text;
                    }
                }
                """);

        try (var loader = compile()) {
            assertEquals("id=x", loader.loadClass("app.Caller").getMethod("run").invoke(null));
        }
    }

    @Test
    void importedExtensionCannotAttachToAnUnrelatedReceiver() throws Exception {
        importedExtension();
        write("app/Caller.accent", """
                package app;
                class Caller {
                    static void run() { "x".label(new domain.Label("id=")); }
                }
                """);

        CompilationResult result = check();
        assertFalse(result.successful(), "An unresolved imported target must not become an Object extension");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Cannot resolve method 'label'")),
                () -> result.diagnostics().toString());
    }

    @Test
    void repeatedExtensionOverloadsBindNamedAndDefaultArgumentsToTheMatchingSignature() throws Exception {
        write("extensions/TextExtensions.accent", """
                package extensions;
                extension String! {
                    String! decorate(int count) { return this + count; }
                }
                extension String! {
                    String! decorate(String! prefix = "<", String! suffix = ">") {
                        return prefix + this + suffix;
                    }
                }
                """);
        write("app/Caller.accent", """
                package app;
                class Caller {
                    static String! run() { return "x".decorate(suffix: "]") + "|" + "y".decorate(); }
                }
                """);

        try (var loader = compile()) {
            assertEquals("<x]|<y>", loader.loadClass("app.Caller").getMethod("run").invoke(null));
        }
    }

    @Test
    void optionalExtensionCallsEvaluateReceiverOnceAndSkipArgumentsForNull() throws Exception {
        write("extensions/TextExtensions.accent", """
                package extensions;
                extension String! {
                    String! decorate(String! prefix, String! suffix = "]") { return prefix + this + suffix; }
                }
                """);
        write("app/Caller.accent", """
                package app;
                class Caller {
                    static int receiverCalls = 0;
                    static int argumentCalls = 0;
                    static String receiver() { Caller.receiverCalls++; return null; }
                    static String! argument() { Caller.argumentCalls++; return "["; }
                    static String absent() { return Caller.receiver()?.decorate(Caller.argument()); }
                    static String present() { return "x"?.decorate(Caller.argument()); }
                }
                """);

        try (var loader = compile()) {
            Class<?> caller = loader.loadClass("app.Caller");
            assertNull(caller.getMethod("absent").invoke(null));
            assertEquals(1, caller.getField("receiverCalls").get(null));
            assertEquals(0, caller.getField("argumentCalls").get(null));
            assertEquals("[x]", caller.getMethod("present").invoke(null));
            assertEquals(1, caller.getField("receiverCalls").get(null));
            assertEquals(1, caller.getField("argumentCalls").get(null));
        }
    }

    @Test
    void optionalVoidExtensionCallsSkipNullAndInvokePresentReceiverOnce() throws Exception {
        write("domain/Counter.accent", """
                package domain;
                class Counter { int total = 0; }
                """);
        write("extensions/CounterExtensions.accent", """
                package extensions;
                import domain.Counter;
                extension Counter! {
                    void add(int value) { this.total = this.total + value; }
                }
                """);
        write("app/Caller.accent", """
                package app;
                class Caller {
                    static int receiverCalls = 0;
                    static int argumentCalls = 0;
                    static domain.Counter receiver(domain.Counter value) { Caller.receiverCalls++; return value; }
                    static int argument() { Caller.argumentCalls++; return 3; }
                    static void run(domain.Counter value) { Caller.receiver(value)?.add(Caller.argument()); }
                }
                """);

        try (var loader = compile()) {
            Class<?> caller = loader.loadClass("app.Caller");
            Class<?> counter = loader.loadClass("domain.Counter");
            var run = caller.getMethod("run", counter);
            run.invoke(null, new Object[]{null});
            assertEquals(1, caller.getField("receiverCalls").get(null));
            assertEquals(0, caller.getField("argumentCalls").get(null));
            Object value = counter.getConstructor().newInstance();
            run.invoke(null, value);
            assertEquals(2, caller.getField("receiverCalls").get(null));
            assertEquals(1, caller.getField("argumentCalls").get(null));
            assertEquals(3, counter.getField("total").get(value));
        }
    }
}
