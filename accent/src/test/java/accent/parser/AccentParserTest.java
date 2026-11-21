package accent.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import accent.lexer.AccentLexer;
import accent.source.SourceFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccentParserTest {
    @Test
    void acceptsNullablePostfixBeforeTheNextStatement() {
        SourceFile source = new SourceFile(Path.of("Sample.accent"), """
                class Sample {
                    void run() {
                        this.lookup()?
                        final value = this.lookup()?
                        if (value) { println(value) }
                    }
                }
                """);
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
    }

    @Test
    void acceptsAccentDeclarationsAndStatementsWithoutTrailingSemicolons() {
        SourceFile source = new SourceFile(Path.of("Sample.accent"), """
                package demo
                import java.util.List

                class Sample {
                    int count = 0

                    void update() {
                        final next = 1
                        this.count = next
                        if (next > 0) {
                            this.count = next
                        }
                        do {
                            this.count = next
                        } while (next < 0)
                        return
                        this.count = next
                    }

                    int value() {
                        return this.count
                    }
                }
                """);
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());

        var unit = parser.parse();

        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
        assertEquals("demo", unit.packageName().orElseThrow());
        assertEquals(1, unit.imports().size());
        assertEquals(1, unit.declarations().size());
    }

    @Test
    void retainsRequiredSeparatorsInTraditionalForHeaders() {
        SourceFile source = new SourceFile(
                Path.of("Loop.accent"),
                "class Loop { void run() { for (i = 0 i < 10; i++) {} } }");
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());

        parser.parse();

        assertTrue(parser.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("loop initializer")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "package demo; class Sample {}",
            "import java.util.List; class Sample {}",
            "class Sample { int value = 0; }",
            "interface Sample { int value(); }",
            "enum Sample { ONE; }",
            "class Sample { void run() { final value = 0; } }",
            "class Sample { int value() { return 0; } }",
            "class Sample { void run() { run(); } }",
            "class Sample { void run() { do {} while (false); } }",
            "class Sample { void run() { for (i = 0; i < 1; i++) { break; } } }",
            "class Sample { void run() { for (i = 0; i < 1; i++) { continue; } } }",
            "class Sample { void run() { ; } }"
    })
    void rejectsSemicolonsOutsideClassicForHeaders(String code) {
        SourceFile source = new SourceFile(Path.of("Sample.accent"), code);
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().stream().anyMatch(d ->
                d.message().contains("Semicolons are only allowed")), () -> parser.diagnostics().toString());
    }

    @Test
    void acceptsClassicForStatementsAndExpressions() {
        SourceFile source = new SourceFile(Path.of("Sample.accent"), """
                class Sample {
                    void run() {
                        for (i = 0; i < 3; i++) { println(i) }
                        for (;;) { break }
                        final values = for (i = 0; i < 3; i++) { yield i }
                    }
                }
                """);
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
    }

    @Test
    void acceptsBodylessMethodsWithoutTerminators() {
        SourceFile source = new SourceFile(Path.of("Sample.accent"),
                "interface Sample { int first() int second() }");
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
    }

    @Test
    void preservesSemicolonsInStringsAndComments() {
        SourceFile source = new SourceFile(Path.of("Sample.accent"), """
                class Sample {
                    String text = ";"
                    // ;
                    /* ; */
                }
                """);
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
    }

    @Test
    void acceptsSemicolonsInJavaSource() {
        SourceFile source = new SourceFile(Path.of("Sample.java"),
                "package demo; public class Sample { int value = 0; void run() { return; } }");
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
        parser.parse();
        assertTrue(parser.diagnostics().isEmpty(), () -> parser.diagnostics().toString());
    }

    @Test
    void stillRequiresSemicolonsInJavaSource() {
        SourceFile source = new SourceFile(Path.of("Sample.java"), "class Sample { int count = 0 }");
        AccentParser parser = new AccentParser(source, new AccentLexer(source).lex().tokens());

        parser.parse();

        assertTrue(parser.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("field declaration")));
    }
}
