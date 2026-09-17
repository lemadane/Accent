package accent.lexer;

import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import accent.source.SourceFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Initial Accent lexer. It recognizes the agreed language keywords and operators.
 * Parsing and Java emission will be built on top of this token stream.
 */
public final class AccentLexer {
    private static final Map<String, TokenType> KEYWORDS = createKeywords();

    private final SourceFile sourceFile;
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private int start;
    private int current;
    private int line = 1;
    private int column = 1;
    private int tokenLine = 1;
    private int tokenColumn = 1;

    public AccentLexer(SourceFile sourceFile) {
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
        this.source = sourceFile.content();
    }

    public LexResult lex() {
        while (!isAtEnd()) {
            start = current;
            tokenLine = line;
            tokenColumn = column;
            scanToken();
        }

        tokens.add(new Token(TokenType.EOF, "", line, column, current, current));
        return new LexResult(tokens, diagnostics);
    }

    private void scanToken() {
        char character = advance();

        switch (character) {
            case '(' -> add(TokenType.LEFT_PAREN);
            case ')' -> add(TokenType.RIGHT_PAREN);
            case '{' -> add(TokenType.LEFT_BRACE);
            case '}' -> add(TokenType.RIGHT_BRACE);
            case '[' -> add(TokenType.LEFT_BRACKET);
            case ']' -> add(TokenType.RIGHT_BRACKET);
            case ',' -> add(TokenType.COMMA);
            case ':' -> add(TokenType.COLON);
            case ';' -> add(TokenType.SEMICOLON);
            case '@' -> add(TokenType.AT);
            case '%' -> add(TokenType.PERCENT);
            case '.' -> add(TokenType.DOT);
            case '?' -> add(match('.') ? TokenType.OPTIONAL_CHAIN
                    : match('?') ? TokenType.NULL_COALESCING
                    : TokenType.QUESTION);
            case '!' -> add(match('=') ? TokenType.BANG_EQUAL : TokenType.BANG);
            case '=' -> add(match('=') ? TokenType.EQUAL_EQUAL : TokenType.ASSIGN);
            case '+' -> add(match('+') ? TokenType.PLUS_PLUS : TokenType.PLUS);
            case '-' -> add(match('-') ? TokenType.MINUS_MINUS
                    : match('>') ? TokenType.ARROW
                    : TokenType.MINUS);
            case '*' -> add(TokenType.STAR);
            case '<' -> add(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS);
            case '>' -> add(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER);
            case '&' -> {
                if (match('&')) {
                    add(TokenType.AND_AND);
                } else {
                    error("ACCENT-L001", "Expected '&' to form '&&'.");
                }
            }
            case '|' -> {
                if (match('|')) {
                    add(TokenType.OR_OR);
                } else {
                    error("ACCENT-L002", "Expected '|' to form '||'.");
                }
            }
            case '/' -> scanSlash();
            case ' ', '\r', '\t' -> {
                // Ignore horizontal whitespace.
            }
            case '\n' -> {
                // Position has already been advanced.
            }
            case '"' -> {
                if (peek() == '"' && peekNext() == '"') {
                    advance();
                    advance();
                    scanTextBlock();
                } else {
                    scanString();
                }
            }
            case '`' -> scanTemplateString();
            case '$' -> {
                if (match('"')) {
                    if (peek() == '"' && peekNext() == '"') {
                        advance();
                        advance();
                        scanInterpolatedTextBlock();
                    } else {
                        scanInterpolatedString('"');
                    }
                } else if (match('`')) {
                    scanInterpolatedString('`');
                } else {
                    scanIdentifier();
                }
            }
            default -> {
                if (isDigit(character)) {
                    scanNumber();
                } else if (isIdentifierStart(character)) {
                    scanIdentifier();
                } else {
                    error("ACCENT-L003", "Unexpected character '%s'.".formatted(character));
                }
            }
        }
    }

    private void scanSlash() {
        if (match('/')) {
            while (peek() != '\n' && !isAtEnd()) {
                advance();
            }
            return;
        }

        if (match('*')) {
            scanBlockComment();
            return;
        }

        add(TokenType.SLASH);
    }

    private void scanBlockComment() {
        while (!isAtEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }

        error("ACCENT-L004", "Unterminated block comment.");
    }

    private void scanString() {
        boolean escaped = false;

        while (!isAtEnd()) {
            char character = advance();

            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"') {
                add(TokenType.STRING);
                return;
            }
        }

        error("ACCENT-L005", "Unterminated string literal.");
    }

    private void scanTextBlock() {
        boolean escaped = false;
        while (!isAtEnd()) {
            char character = advance();
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"' && peek() == '"' && peekNext() == '"') {
                advance();
                advance();
                add(TokenType.STRING);
                return;
            }
        }
        error("ACCENT-L010", "Unterminated text block literal.");
    }

    private void scanTemplateString() {
        boolean escaped = false;
        while (!isAtEnd()) {
            char character = advance();
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '`') {
                add(TokenType.TEMPLATE_STRING);
                return;
            }
        }
        error("ACCENT-L006", "Unterminated template string literal.");
    }

    private void scanInterpolatedString(char quoteChar) {
        add(TokenType.INTERPOLATED_STRING_START);

        int textStart = current;
        int textLine = line;
        int textColumn = column;

        while (!isAtEnd()) {
            if (peek() == '{' && peekNext() == '{') {
                advance();
                advance();
                continue;
            }
            if (peek() == '}' && peekNext() == '}') {
                advance();
                advance();
                continue;
            }
            if (peek() == '}') {
                error("ACCENT-L007", "Unexpected '}' in interpolated string. Use '}}' for a literal closing brace.");
                advance();
                continue;
            }
            if (peek() == '{') {
                // Emit current text if any
                if (current > textStart) {
                    addInterpolatedStringText(textStart, current, textLine, textColumn);
                }

                // Consume '{' and emit INTERPOLATION_START
                start = current;
                tokenLine = line;
                tokenColumn = column;
                advance();
                add(TokenType.INTERPOLATION_START);

                // Tokenize expression with brace tracking
                int braceDepth = 1;
                while (!isAtEnd() && braceDepth > 0) {
                    start = current;
                    tokenLine = line;
                    tokenColumn = column;

                    char nextChar = peek();
                    if (nextChar == '{') {
                        braceDepth++;
                    } else if (nextChar == '}') {
                        braceDepth--;
                        if (braceDepth == 0) {
                            break;
                        }
                    }

                    scanToken();
                }

                if (braceDepth > 0) {
                    error("ACCENT-L008", "Unterminated interpolation expression. Expected '}'.");
                    return;
                }

                // Consume '}' and emit INTERPOLATION_END
                start = current;
                tokenLine = line;
                tokenColumn = column;
                advance();
                add(TokenType.INTERPOLATION_END);

                // Reset text indices
                textStart = current;
                textLine = line;
                textColumn = column;
                continue;
            }

            char c = advance();
            if (c == '\\') {
                if (!isAtEnd()) {
                    advance();
                }
            } else if (c == quoteChar) {
                // Emit current text if any
                if (current - 1 > textStart) {
                    addInterpolatedStringText(textStart, current - 1, textLine, textColumn);
                }
                // Emit INTERPOLATED_STRING_END
                start = current - 1;
                tokenLine = line;
                tokenColumn = column - 1;
                add(TokenType.INTERPOLATED_STRING_END);
                return;
            }
        }

        error("ACCENT-L009", "Unterminated interpolated string.");
    }

    private void scanInterpolatedTextBlock() {
        add(TokenType.INTERPOLATED_STRING_START);

        int textStart = current;
        int textLine = line;
        int textColumn = column;

        while (!isAtEnd()) {
            if (peek() == '{' && peekNext() == '{') {
                advance();
                advance();
                continue;
            }
            if (peek() == '}' && peekNext() == '}') {
                advance();
                advance();
                continue;
            }
            if (peek() == '}') {
                error("ACCENT-L007", "Unexpected '}' in interpolated string. Use '}}' for a literal closing brace.");
                advance();
                continue;
            }
            if (peek() == '{') {
                if (current > textStart) {
                    addInterpolatedStringText(textStart, current, textLine, textColumn);
                }

                start = current;
                tokenLine = line;
                tokenColumn = column;
                advance();
                add(TokenType.INTERPOLATION_START);

                int braceDepth = 1;
                while (!isAtEnd() && braceDepth > 0) {
                    start = current;
                    tokenLine = line;
                    tokenColumn = column;

                    char nextChar = peek();
                    if (nextChar == '{') {
                        braceDepth++;
                    } else if (nextChar == '}') {
                        braceDepth--;
                        if (braceDepth == 0) {
                            break;
                        }
                    }

                    scanToken();
                }

                if (braceDepth > 0) {
                    error("ACCENT-L008", "Unterminated interpolation expression. Expected '}'.");
                    return;
                }

                start = current;
                tokenLine = line;
                tokenColumn = column;
                advance();
                add(TokenType.INTERPOLATION_END);

                textStart = current;
                textLine = line;
                textColumn = column;
                continue;
            }

            if (peek() == '"' && peekNext() == '"' && current + 2 < source.length() && source.charAt(current + 2) == '"') {
                if (current > textStart) {
                    addInterpolatedStringText(textStart, current, textLine, textColumn);
                }
                advance();
                advance();
                advance();
                start = current - 3;
                tokenLine = line;
                tokenColumn = column - 3;
                add(TokenType.INTERPOLATED_STRING_END);
                return;
            }

            char c = advance();
            if (c == '\\') {
                if (!isAtEnd()) {
                    advance();
                }
            }
        }

        error("ACCENT-L009", "Unterminated interpolated text block.");
    }

    private void addInterpolatedStringText(int startIdx, int endIdx, int l, int col) {
        String raw = source.substring(startIdx, endIdx);
        String processed = raw.replace("{{", "{").replace("}}", "}");
        tokens.add(new Token(TokenType.INTERPOLATED_STRING_TEXT, processed, l, col, startIdx, endIdx));
    }

    private void scanNumber() {
        while (isDigit(peek())) {
            advance();
        }

        if (peek() == '.' && isDigit(peekNext())) {
            advance();
            while (isDigit(peek())) {
                advance();
            }
        }

        add(TokenType.NUMBER);
    }

    private void scanIdentifier() {
        while (!isAtEnd() && isIdentifierPart(peek())) {
            advance();
        }

        String lexeme = source.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(lexeme, TokenType.IDENTIFIER);
        tokens.add(new Token(type, lexeme, tokenLine, tokenColumn, start, current));
    }

    private char advance() {
        char character = source.charAt(current++);
        if (character == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return character;
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) {
            return false;
        }
        advance();
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private void add(TokenType type) {
        tokens.add(new Token(
                type,
                source.substring(start, current),
                tokenLine,
                tokenColumn,
                start,
                current));
    }

    private void error(String code, String message) {
        diagnostics.add(new Diagnostic(
                DiagnosticSeverity.ERROR,
                code,
                message,
                tokenLine,
                tokenColumn));
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isJavaIdentifierStart(character);
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isJavaIdentifierPart(character);
    }

    private static Map<String, TokenType> createKeywords() {
        Map<String, TokenType> keywords = new HashMap<>();
        keywords.put("class", TokenType.CLASS);
        keywords.put("singleton", TokenType.SINGLETON);
        keywords.put("model", TokenType.MODEL);
        keywords.put("interface", TokenType.INTERFACE);
        keywords.put("record", TokenType.RECORD);
        keywords.put("enum", TokenType.ENUM);
        keywords.put("extension", TokenType.EXTENSION);
        keywords.put("final", TokenType.FINAL);
        keywords.put("var", TokenType.VAR);
        keywords.put("if", TokenType.IF);
        keywords.put("else", TokenType.ELSE);
        keywords.put("try", TokenType.TRY);
        keywords.put("catch", TokenType.CATCH);
        keywords.put("finally", TokenType.FINALLY);
        keywords.put("for", TokenType.FOR);
        keywords.put("while", TokenType.WHILE);
        keywords.put("do", TokenType.DO);
        keywords.put("yield", TokenType.YIELD);
        keywords.put("async", TokenType.ASYNC);
        keywords.put("await", TokenType.AWAIT);
        keywords.put("return", TokenType.RETURN);
        keywords.put("new", TokenType.NEW);
        keywords.put("public", TokenType.PUBLIC);
        keywords.put("protected", TokenType.PROTECTED);
        keywords.put("private", TokenType.PRIVATE);
        keywords.put("static", TokenType.STATIC);
        keywords.put("void", TokenType.VOID);
        keywords.put("true", TokenType.TRUE);
        keywords.put("false", TokenType.FALSE);
        keywords.put("null", TokenType.NULL);
        keywords.put("package", TokenType.PACKAGE);
        keywords.put("import", TokenType.IMPORT);
        keywords.put("break", TokenType.BREAK);
        keywords.put("continue", TokenType.CONTINUE);
        keywords.put("this", TokenType.THIS);
        keywords.put("super", TokenType.SUPER);
        keywords.put("throws", TokenType.THROWS);
        keywords.put("throw", TokenType.THROW);
        keywords.put("instanceof", TokenType.INSTANCEOF);
        keywords.put("not",  TokenType.NOT);
        keywords.put("and",  TokenType.AND);
        keywords.put("or",   TokenType.OR);
        keywords.put("nand", TokenType.NAND);
        keywords.put("nor",  TokenType.NOR);
        keywords.put("xor",  TokenType.XOR);
        keywords.put("xnor", TokenType.XNOR);
        keywords.put("generator", TokenType.GENERATOR);
        keywords.put("emit",      TokenType.EMIT);
        return Map.copyOf(keywords);
    }
}
