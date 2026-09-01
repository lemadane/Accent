package accent.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import accent.source.SourceFile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccentLexerTest {
    @Test
    void recognizesCoreAccentFeatures() {
        SourceFile source = new SourceFile(
                Path.of("Features.accent"),
                """
                extension String! {
                    String! normalized() {
                        final result = this?.trim() ?? \"\";
                        return result;
                    }
                }

                final future = async service.load();
                final value = await future;

                final values = for (var i = 0; i < 10; i++) {
                    yield i;
                };
                """);

        LexResult result = new AccentLexer(source).lex();
        List<TokenType> types = result.tokens().stream().map(Token::type).toList();

        assertTrue(result.successful());
        assertTrue(types.contains(TokenType.EXTENSION));
        assertTrue(types.contains(TokenType.BANG));
        assertTrue(types.contains(TokenType.OPTIONAL_CHAIN));
        assertTrue(types.contains(TokenType.NULL_COALESCING));
        assertTrue(types.contains(TokenType.ASYNC));
        assertTrue(types.contains(TokenType.AWAIT));
        assertTrue(types.contains(TokenType.FOR));
        assertTrue(types.contains(TokenType.YIELD));
        assertEquals(TokenType.EOF, types.getLast());
    }

    @Test
    void reportsUnterminatedString() {
        SourceFile source = new SourceFile(
                Path.of("Broken.accent"),
                "final text = \"broken;");

        LexResult result = new AccentLexer(source).lex();

        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("ACCENT-L005")));
    }
}
