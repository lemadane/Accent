package accent.lexer;

import java.util.Objects;

public record Token(TokenType type, String lexeme, int line, int column, int startOffset, int endOffset) {
    public Token {
        type = Objects.requireNonNull(type, "type");
        lexeme = Objects.requireNonNull(lexeme, "lexeme");
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException("Token positions are one-based");
        }
        if (startOffset < 0 || endOffset < 0) {
            throw new IllegalArgumentException("Offsets must be non-negative");
        }
    }

    public Token(TokenType type, String lexeme, int line, int column) {
        this(type, lexeme, line, column, 0, 0);
    }
}
