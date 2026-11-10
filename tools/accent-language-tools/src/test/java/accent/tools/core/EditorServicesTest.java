package accent.tools.core;

import static org.junit.jupiter.api.Assertions.*;

import accent.ast.Ast.CompilationUnit;
import accent.tools.core.AccentLanguageService.ProjectContext;
import accent.tools.core.AccentLanguageService.CompilationResult;
import accent.tools.core.CompletionProvider.CompletionItem;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

class EditorServicesTest {

    private static final String BASE_MEMBERS = """
            package owner;
            class Base {
                int visibleField;
                private int _packageField;
                protected int __protectedField;
                void visibleMethod() {}
                private void _packageMethod() {}
                protected void __protectedMethod() {}
            }
            """;

    @Test
    void elseFallbackProducesGeneratedJava() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("Fallback.accent");
        context.putFile(path, """
                class Fallback { String value(String name?) { return name? else "Guest"; } }
                """);
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertTrue(context.getGeneratedJava(path).contains("Guest"));
    }

    @Test
    void thenElseExpressionsHaveCompletionAndGeneratedJava() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("Choice.accent");
        context.putFile(path, """
                class Choice { String value(boolean active) { return active then "ACTIVE" else "INACTIVE"; } }
                """);
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertTrue(context.getGeneratedJava(path).contains(" ? "));
        assertMembersPresent(CompletionProvider.getCompletions(result.unit(), result.analyzer(), 1, 1), "then", "else");
    }

    @Test
    void bodylessDataDeclarationsHaveDiagnosticsAndGeneratedJava() {
        for (String kind : List.of("record", "model")) {
            ProjectContext context = new ProjectContext(List.of());
            Path path = Path.of("Data.accent");
            context.putFile(path, kind + " Data(String name)");
            var result = context.analyze(path, false);
            assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
            assertTrue(context.getGeneratedJava(path).contains("Data"));
            context.putFile(path, kind + " Data(String name) {}");
            var invalid = context.analyze(path, false);
            assertFalse(invalid.diagnostics().isEmpty());
        }
    }

    @Test
    void inferredForInitializersAppearInGeneratedJava() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("InferredFor.accent");
        context.putFile(path, "class InferredFor { static void run() { for (i = 0; i < 3; i++) { println(i); } } }");
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String generated = context.getGeneratedJava(path);
        assertTrue(generated.contains("var i = 0"), generated);
    }

    @Test
    void eachLoopsHaveKeywordCompletionAndGeneratedJava() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("EachEditor.accent");
        context.putFile(path, "import java.util.List; class EachEditor { static void run() { each (item from List.of(1)) { println(item); } else { println(0); } } }");
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertTrue(context.getGeneratedJava(path).contains("for ("));
        assertMembersPresent(CompletionProvider.getCompletions(result.unit(), result.analyzer(), 1, 1), "each", "from");
    }

    @Test
    void staticImportsResolveInGeneratedJava() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("Imported.accent");
        context.putFile(path, "import static java.lang.Math.sqrt; import static java.lang.Math.PI; class Imported { static double run() { return sqrt(PI); } }");
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String generated = context.getGeneratedJava(path);
        assertTrue(generated.contains("java.lang.Math.sqrt"), generated);
        assertTrue(generated.contains("java.lang.Math.PI"), generated);
    }

    @Test
    void nativeConsoleAndLoggerHaveCompletionAndGeneratedJavaWithoutAnnotations() {
        var members = complete(new ProjectContext(List.of()), "NativeEditor.accent", """
                class NativeEditor {
                    static void run() { log.<caret> }
                }
                """);
        assertMembersPresent(members, "info", "warn", "put", "get()?", "remove", "clear", "clearContext", "withContext");
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("NativeEditor.accent");
        context.putFile(path, "class NativeEditor { static void run() { println(\"hello\"); log(\"hello\"); } }");
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        String java = context.getGeneratedJava(path);
        assertTrue(java.contains("java.lang.System.out.println"), java);
        assertTrue(java.contains("accent.logging.Logger.log(\"hello\")"), java);
        var globals = CompletionProvider.getCompletions(result.unit(), result.analyzer(), 1, 1);
        assertMembersPresent(globals, "println", "print", "log", "Logger");
    }

    @Test
    void staticCompletionOnlyOffersOwnStaticMembers() {
        String code = """
                class Counter {
                    static int count = 0;
                    static void increment() {}
                    int value;
                    void inspect() { static.<caret> }
                }
                """;
        var members = complete(new ProjectContext(List.of()), "Counter.accent", code);
        assertMembersPresent(members, "count", "increment");
        assertMembersAbsent(members, "value", "inspect", "getClass()?");
        var instanceMembers = complete(new ProjectContext(List.of()), "Counter.accent",
                code.replace("static.<caret>", "this.<caret>"));
        assertMembersPresent(instanceMembers, "value", "inspect");
        assertMembersAbsent(instanceMembers, "count", "increment");
    }

    @Test
    void completionPreservesNonNullMethodSuffix() {
        List<CompletionItem> members = complete(new ProjectContext(List.of()), "Sample.accent", """
                class Sample {
                    String name() { return "John"; }
                    String nullableName?() { return null; }
                    void inspect() { this.<caret> }
                }
                """);
        assertMembersPresent(members, "name", "nullableName()?");
        assertMembersAbsent(members, "name!", "nullableName!");
    }

    @Test
    void completionPreservesCollectionSuffix() {
        List<CompletionItem> members = complete(new ProjectContext(List.of()), "Sample.accent", """
                class Sample {
                    private java.util.List<String> _names = new java.util.ArrayList<String>();
                    void inspect() { this.<caret> }
                }
                """);
        assertMembersPresent(members, "_names");
        assertMembersAbsent(members, "_names!", "_names!!");
        List<CompletionItem> methods = complete(new ProjectContext(List.of()), "Names.accent", """
                class Names {
                    void inspect(java.util.List<String> names) { names.<caret> }
                }
                """);
        assertMembersPresent(methods, "get");
        assertMembersAbsent(methods, "get!");
    }

    @Test
    void completionPreservesNonNullFieldSuffix() {
        List<CompletionItem> members = complete(new ProjectContext(List.of()), "Sample.accent", """
                class Sample {
                    private String _name = "John";
                    void inspect() { this.<caret> }
                }
                """);
        assertMembersPresent(members, "_name");
        assertMembersAbsent(members, "_name!");
    }

    @Test
    void testErrorTolerantParsingAndCompletion() {
        // Incomplete code ending in a trailing dot
        String code = 
            "package test;\n" +
            "class Dummy {\n" +
            "    void run() {\n" +
            "        String text = \"hello\";\n" +
            "        text.\n" +
            "    }\n" +
            "}\n";

        Path path = Paths.get("Dummy.accent");
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(path, code);

        CompilationResult result = context.analyze(path, true);
        assertNotNull(result.unit(), "Should produce a compilation unit even with syntax errors");
        assertFalse(result.diagnostics().isEmpty(), "Should capture parser diagnostics");

        // The line of 'text.' is 5, column is 14 (right after the dot)
        List<CompletionItem> completions = CompletionProvider.getCompletions(result.unit(), result.analyzer(), 5, 14);
        assertNotNull(completions);
        
        boolean hasLength = completions.stream().anyMatch(c -> "length".equals(c.label()));
        assertTrue(hasLength, "Should suggest methods like length on String");
    }

    @Test
    void testLoggingHoverAndCompletion() {
        String code = 
            "package test;\n" +
            "import accent.logging.Logging;\n" +
            "@Logging\n" +
            "class LogService {\n" +
            "    void perform() {\n" +
            "        log.info(\"test\");\n" +
            "    }\n" +
            "}\n";

        Path path = Paths.get("LogService.accent");
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(path, code);

        CompilationResult result = context.analyze(path, true);
        
        // Hover at line 6 (log.info), column 9 (where 'log' is)
        String hover = HoverProvider.getHover(result.unit(), result.analyzer(), 6, 9);
        assertNotNull(hover);
        assertTrue(hover.contains("Synthetic Field") && hover.contains("Logger"), "Hover should describe the synthetic log field");

        // completions right after 'log.'
        List<CompletionItem> completions = CompletionProvider.getCompletions(result.unit(), result.analyzer(), 6, 13);
        assertNotNull(completions);
        boolean hasInfo = completions.stream().anyMatch(c -> "info".equals(c.label()));
        assertTrue(hasInfo, "Should suggest logging methods like info");
    }
    @Test
    void asyncMembersAndKeywordCompletionsMatchThePublicApi() {
        String code = """
                import accent.runtime.Async;
                class AsyncEditor {
                    void inspect() {
                        final customerAsync = Async.run(() -> "Alice");
                        final customer = customerAsync.await();
                        customerAsync.
                    }
                }
                """;
        Path path = Path.of("AsyncEditor.accent");
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(path, code);
        CompilationResult result = context.analyze(path, true);
        int dotColumn = code.lines().toList().get(5).indexOf('.') + 2;
        List<CompletionItem> members = CompletionProvider.getCompletions(result.unit(), result.analyzer(), 6, dotColumn);
        for (String name : List.of("await", "cancel", "isDone", "isCancelled")) {
            assertTrue(members.stream().anyMatch(item -> item.label().equals(name)), name);
        }
        assertTrue(members.stream().filter(item -> item.label().equals("await"))
                .allMatch(item -> item.detail().contains("String")), "await completion should expose the result type");
        List<CompletionItem> keywords = CompletionProvider.getCompletions(result.unit(), result.analyzer(), 1, 1);
        for (String visibility : List.of("public", "protected", "private", "this", "super")) {
            assertTrue(keywords.stream().anyMatch(item -> item.label().equals(visibility)
                    && item.kind().equals("Keyword")), visibility);
        }
        assertTrue(keywords.stream().anyMatch(item -> item.label().equals("async") && item.kind().equals("Keyword")));
        assertTrue(keywords.stream().anyMatch(item -> item.label().equals("await") && item.kind().equals("Keyword")));
    }

    @Test
    void javaMemberCompletionIncludesInheritedPublicApiAndHidesInternals() {
        List<CompletionItem> members = complete(new ProjectContext(List.of()), "Dummy.accent", """
                package test;
                class Dummy {
                    void inspect() {
                        String text = "hello";
                        text.<caret>
                    }
                }
                """);

        assertMembersPresent(members, "length", "getClass()?", "wait");
        assertEquals(1, members.stream().filter(member -> member.label().equals("toString()?")).count(),
                "Overrides and inherited methods with identical completion details should appear once");
        assertMembersAbsent(members, "value", "coder", "isLatin1", "hash", "serialVersionUID",
                "COMPACT_STRINGS", "clone", "finalize");
    }

    @Test
    void samePackageMemberCompletionIncludesPackageAndProtectedMembers() {
        List<CompletionItem> members = complete(baseContext(), "Neighbor.accent", """
                package owner;
                class Neighbor {
                    void inspect(Base value) {
                        value.<caret>
                    }
                }
                """);

        assertMembersPresent(members, "visibleField", "visibleMethod", "_packageField", "_packageMethod",
                "__protectedField", "__protectedMethod");
    }

    @Test
    void unrelatedPackageMemberCompletionHidesPackageAndProtectedMembers() {
        List<CompletionItem> members = complete(baseContext(), "Caller.accent", """
                package other;
                import owner.Base;
                class Caller {
                    void inspect(Base value) {
                        value.<caret>
                    }
                }
                """);

        assertMembersPresent(members, "visibleField", "visibleMethod", "getClass()?");
        assertMembersAbsent(members, "_packageField", "_packageMethod", "__protectedField", "__protectedMethod");
    }

    @Test
    void superCompletionIncludesInheritedProtectedMembers() {
        List<CompletionItem> members = complete(baseContext(), "Child.accent", """
                package other;
                import owner.Base;
                class Child extends Base {
                    void inspect() {
                        super.<caret>
                    }
                }
                """);

        assertMembersPresent(members, "visibleField", "visibleMethod", "__protectedField", "__protectedMethod", "getClass()?");
        assertMembersAbsent(members, "_packageField", "_packageMethod");
    }

    @Test
    void subclassCannotCompleteProtectedMembersOnAnUnrelatedBaseReceiver() {
        List<CompletionItem> members = complete(baseContext(), "Child.accent", """
                package other;
                import owner.Base;
                class Child extends Base {
                    void inspect(Base value) {
                        value.<caret>
                    }
                }
                """);

        assertMembersPresent(members, "visibleField", "visibleMethod");
        assertMembersAbsent(members, "_packageField", "_packageMethod", "__protectedField", "__protectedMethod");
    }

    @Test
    void thisCompletionOnlyOffersCurrentDeclarationsAndOverrides() {
        List<CompletionItem> members = complete(baseContext(), "Child.accent", """
                package other;
                import owner.Base;
                class Child extends Base {
                    private int _own;
                    public void visibleMethod() {}
                    void inspect() { this.<caret> }
                }
                """);
        assertMembersPresent(members, "_own", "visibleMethod", "inspect");
        assertMembersAbsent(members, "visibleField", "__protectedField", "__protectedMethod", "getClass()?");
    }

    @Test
    void invalidInheritedThisAndMissingPrefixProduceEditorDiagnostics() {
        ProjectContext context = baseContext();
        Path path = Path.of("Child.accent");
        context.putFile(path, """
                package other;
                import owner.Base;
                class Child extends Base {
                    int inspect() { return this.visibleField; }
                }
                """);
        assertTrue(context.analyze(path, false).diagnostics().stream()
                .anyMatch(d -> d.message().contains("super.visibleField")));
        context.putFile(path, "class Child { private int count; }");
        assertTrue(context.analyze(path, false).diagnostics().stream()
                .anyMatch(d -> d.message().contains("must start with '_'")));
    }

    private static ProjectContext baseContext() {
        ProjectContext context = new ProjectContext(List.of());
        Path path = Path.of("Base.accent");
        context.putFile(path, BASE_MEMBERS);
        assertTrue(context.analyze(path, false).diagnostics().isEmpty());
        return context;
    }

    private static List<CompletionItem> complete(ProjectContext context, String filename, String markedSource) {
        int cursor = markedSource.indexOf("<caret>");
        assertTrue(cursor >= 0, "Missing completion cursor");
        String prefix = markedSource.substring(0, cursor);
        int line = (int) prefix.chars().filter(c -> c == '\n').count() + 1;
        int column = cursor - prefix.lastIndexOf('\n');
        Path path = Path.of(filename);
        context.putFile(path, markedSource.replace("<caret>", ""));
        CompilationResult result = context.analyze(path, true);
        return CompletionProvider.getCompletions(result.unit(), result.analyzer(), line, column);
    }

    private static void assertMembersPresent(List<CompletionItem> members, String... names) {
        List<String> labels = members.stream().map(CompletionItem::label).toList();
        for (String name : names) assertTrue(labels.contains(name), () -> "Missing " + name + ": " + labels);
    }

    private static void assertMembersAbsent(List<CompletionItem> members, String... names) {
        List<String> labels = members.stream().map(CompletionItem::label).toList();
        for (String name : names) assertFalse(labels.contains(name), () -> "Inaccessible " + name + ": " + labels);
    }
}
