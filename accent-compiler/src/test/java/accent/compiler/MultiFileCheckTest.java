package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiFileCheckTest {
    @Test
    void resolvesCrossFileReferencesAndPreservesInputOrder() {
        SourceFile caller = source("Caller", "class Caller { int call() { return new Answer().value(); } }");
        SourceFile answer = source("Answer", "class Answer { int value() { return 42; } }");
        List<SourceFile> sources = List.of(caller, answer);
        AccentCompiler compiler = new AccentCompiler();

        var perFile = compiler.checkEach(sources);
        CompilationResult result = compiler.check(sources);

        assertEquals(sources, new ArrayList<>(perFile.keySet()));
        assertTrue(perFile.values().stream().allMatch(List::isEmpty), perFile::toString);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertEquals(answer, result.compilationUnit().orElseThrow().sourceFile());
    }

    @Test
    void keepsSemanticDiagnosticsOnTheFailingFile() {
        SourceFile good = source("Good", "class Good {}");
        SourceFile broken = source("Broken", "class Broken { void run() { final value = 1; value = 2; } }");
        AccentCompiler compiler = new AccentCompiler();

        var perFile = compiler.checkEach(List.of(good, broken));
        CompilationResult result = compiler.check(List.of(good, broken));

        assertTrue(perFile.get(good).isEmpty());
        assertFalse(perFile.get(broken).isEmpty());
        assertEquals(perFile.get(broken), result.diagnostics());
        assertFalse(result.successful());
        assertTrue(result.compilationUnit().isEmpty());
    }

    @Test
    void keepsLexicalAndParserDiagnosticsOnTheirSources() {
        SourceFile lexical = source("Lexical", "class Lexical { # }");
        SourceFile syntax = source("Syntax", "class Syntax { int value = ; }");
        SourceFile good = source("Good", "class Good {}");

        var perFile = new AccentCompiler().checkEach(List.of(lexical, syntax, good));

        assertTrue(perFile.get(lexical).stream().anyMatch(d -> d.code().equals("ACCENT-L003")));
        assertTrue(perFile.get(syntax).stream().anyMatch(d -> d.code().equals("ACCENT-P001")));
        assertTrue(perFile.get(good).isEmpty());
    }

    @Test
    void acceptsAnEmptySourceList() {
        AccentCompiler compiler = new AccentCompiler();

        assertTrue(compiler.checkEach(List.of()).isEmpty());
        CompilationResult result = compiler.check(List.of());
        assertTrue(result.successful());
        assertTrue(result.compilationUnit().isEmpty());
    }

    private static SourceFile source(String name, String content) {
        return new SourceFile(Path.of(name + ".accent"), content);
    }
}
