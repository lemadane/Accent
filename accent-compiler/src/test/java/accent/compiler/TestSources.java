package accent.compiler;

import accent.lexer.AccentLexer;
import accent.lexer.Token;
import accent.lexer.TokenType;
import accent.source.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper: Accent allows exactly one top-level type per file. Many test fixtures are written as a
 * single snippet with several types; this splits such a snippet into one source per type, each sharing
 * the snippet's package/import header. Extensions stay with the first type.
 */
public final class TestSources {
    private TestSources() {}

    /** Splits {@code code} into {@code TypeName -> source} (in declaration order). */
    public static Map<String, String> split(String code) {
        List<Token> tokens = new AccentLexer(new SourceFile(Path.of("Split.accent"), code)).lex().tokens();
        int i = 0;
        // Skip package / import statements
        while (i < tokens.size() && (tokens.get(i).type() == TokenType.PACKAGE || tokens.get(i).type() == TokenType.IMPORT)) {
            while (i < tokens.size() && tokens.get(i).type() != TokenType.SEMICOLON) i++;
            i++;
        }
        if (i >= tokens.size() || tokens.get(i).type() == TokenType.EOF) {
            return Map.of("Module", code);
        }
        String header = code.substring(0, tokens.get(i).startOffset());

        List<int[]> segments = new ArrayList<>();
        int start = tokens.get(i).startOffset();
        int depth = 0;
        int parentheses = 0;
        boolean dataDeclaration = false;
        for (; i < tokens.size() && tokens.get(i).type() != TokenType.EOF; i++) {
            Token t = tokens.get(i);
            if (depth == 0 && parentheses == 0
                    && (t.type() == TokenType.RECORD || t.type() == TokenType.MODEL)) {
                dataDeclaration = true;
            }
            if (t.type() == TokenType.LEFT_PAREN) parentheses++;
            if (t.type() == TokenType.RIGHT_PAREN) {
                parentheses--;
                if (dataDeclaration && depth == 0 && parentheses == 0
                        && i + 1 < tokens.size()
                        && tokens.get(i + 1).type() != TokenType.LEFT_BRACE
                        && !tokens.get(i + 1).lexeme().equals("implements")) {
                    segments.add(new int[]{start, t.endOffset()});
                    start = tokens.get(i + 1).startOffset();
                    dataDeclaration = false;
                }
            }
            if (t.type() == TokenType.LEFT_BRACE) depth++;
            else if (t.type() == TokenType.RIGHT_BRACE) {
                depth--;
                if (depth == 0) {
                    dataDeclaration = false;
                    segments.add(new int[]{start, t.endOffset()});
                    start = i + 1 < tokens.size() ? tokens.get(i + 1).startOffset() : code.length();
                }
            }
        }
        if (start < code.length() && !code.substring(start).isBlank()) {
            segments.add(new int[]{start, code.length()});
        }

        Map<String, StringBuilder> files = new LinkedHashMap<>();
        StringBuilder pendingExtensions = new StringBuilder();
        for (int[] seg : segments) {
            String text = code.substring(seg[0], seg[1]);
            String name = typeName(text);
            if (name == null) {
                if (files.isEmpty()) pendingExtensions.append(text).append('\n');
                else files.values().iterator().next().append('\n').append(text).append('\n');
                continue;
            }
            StringBuilder sb = new StringBuilder(header);
            if (files.isEmpty()) sb.append(pendingExtensions);
            sb.append(text).append('\n');
            files.put(name, sb);
        }
        Map<String, String> result = new LinkedHashMap<>();
        files.forEach((k, v) -> result.put(k, v.toString()));
        if (result.isEmpty()) result.put("Module", code);
        return result;
    }

    /** Writes each top-level type of {@code code} to {@code dir/<Type>.accent}. */
    public static void writeSplit(Path dir, String code) throws IOException {
        Files.createDirectories(dir);
        for (Map.Entry<String, String> e : split(code).entrySet()) {
            Files.writeString(dir.resolve(e.getKey() + ".accent"), e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** In-memory variant for {@link AccentCompiler#check(List)}. */
    public static List<SourceFile> sources(String code) {
        List<SourceFile> list = new ArrayList<>();
        split(code).forEach((name, text) -> list.add(new SourceFile(Path.of(name + ".accent"), text)));
        return list;
    }

    private static String typeName(String declaration) {
        List<Token> tokens = new AccentLexer(new SourceFile(Path.of("Decl.accent"), declaration)).lex().tokens();
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.EXTENSION) return null;
            if (type == TokenType.CLASS || type == TokenType.INTERFACE || type == TokenType.RECORD
                    || type == TokenType.ENUM || type == TokenType.MODEL) {
                return i + 1 < tokens.size() ? tokens.get(i + 1).lexeme() : null;
            }
        }
        return null;
    }
}
