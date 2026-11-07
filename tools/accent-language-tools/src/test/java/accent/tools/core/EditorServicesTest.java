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
        assertTrue(keywords.stream().anyMatch(item -> item.label().equals("async") && item.kind().equals("Keyword")));
        assertTrue(keywords.stream().anyMatch(item -> item.label().equals("await") && item.kind().equals("Keyword")));
    }

}
